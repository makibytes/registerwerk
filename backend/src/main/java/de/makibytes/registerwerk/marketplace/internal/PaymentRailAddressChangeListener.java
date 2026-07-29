package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.marketplace.api.DappPaymentMethod;
import de.makibytes.registerwerk.marketplace.api.DappPaymentMethodRepository;
import de.makibytes.registerwerk.marketplace.api.DappReviewEvent;
import de.makibytes.registerwerk.marketplace.api.DappReviewEventRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersion;
import de.makibytes.registerwerk.marketplace.api.DappVersionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersionStatus;
import de.makibytes.registerwerk.payment.events.PaymentRailEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A payment rail's onchain chain-address mapping can change after a dApp version referencing
 * it by code was already reviewed and PUBLISHED — the manifest signature only covers the rail
 * code, never the resolved address, so nothing re-validates a live listing when the address
 * moves. This flags every affected PUBLISHED version in its own review trail so an operator
 * sees the drift, since the backend has no registry of which onchain dApp instances actually
 * depend on the rail and therefore cannot re-verify or re-anchor them automatically.
 */
@Component
class PaymentRailAddressChangeListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentRailAddressChangeListener.class);

    private final DappPaymentMethodRepository paymentMethodRepository;
    private final DappVersionRepository versionRepository;
    private final DappReviewEventRepository reviewEventRepository;

    PaymentRailAddressChangeListener(
            DappPaymentMethodRepository paymentMethodRepository,
            DappVersionRepository versionRepository,
            DappReviewEventRepository reviewEventRepository) {
        this.paymentMethodRepository = paymentMethodRepository;
        this.versionRepository = versionRepository;
        this.reviewEventRepository = reviewEventRepository;
    }

    @EventListener
    void onPaymentRailEvent(PaymentRailEvent event) {
        if (!"UPDATED".equals(event.action()) || !event.payload().containsKey("oldChainAddresses")) {
            return;
        }
        String railCode = (String) event.payload().get("code");
        if (railCode == null) {
            return;
        }

        for (DappPaymentMethod method : paymentMethodRepository.findByRailCode(railCode)) {
            DappVersion version = versionRepository.findById(method.getVersionId()).orElse(null);
            if (version == null || version.getStatus() != DappVersionStatus.PUBLISHED) {
                continue;
            }
            DappReviewEvent reviewEvent = new DappReviewEvent();
            reviewEvent.setVersionId(version.getId());
            reviewEvent.setAction("PAYMENT_RAIL_ADDRESS_DRIFT");
            reviewEvent.setActorId(event.actorId());
            reviewEvent.setNotes("Payment rail '" + railCode + "' chain-address mapping changed after this "
                    + "version was published: oldChainAddresses=" + event.payload().get("oldChainAddresses")
                    + " newChainAddresses=" + event.payload().get("newChainAddresses"));
            reviewEventRepository.save(reviewEvent);
            log.warn("PUBLISHED dApp version {} references payment rail '{}' whose chain-address mapping just "
                    + "changed — operator should verify this listing still settles to the intended address",
                    version.getId(), railCode);
        }
    }
}
