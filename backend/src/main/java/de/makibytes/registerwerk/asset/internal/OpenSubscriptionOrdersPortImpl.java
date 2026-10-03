package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.OpenSubscriptionOrdersPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Answers {@link OpenSubscriptionOrdersPort} for the register handover package (T3-07). */
@Component
class OpenSubscriptionOrdersPortImpl implements OpenSubscriptionOrdersPort {

    private final SubscriptionOrderRepository repository;

    OpenSubscriptionOrdersPortImpl(SubscriptionOrderRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenOrder> openOrders(UUID assetId) {
        return repository.findByAssetIdAndStatusIn(assetId,
                        EnumSet.of(SubscriptionOrder.Status.SUBMITTED, SubscriptionOrder.Status.ALLOCATED,
                                // C7: a mint in flight (or to be retried) must not be overtaken by a register handover
                                SubscriptionOrder.Status.SETTLEMENT_PENDING, SubscriptionOrder.Status.SETTLEMENT_FAILED)).stream()
                .map(o -> new OpenOrder(o.getId(), o.getInvestorEntityId(), o.getWalletAddress(),
                        o.getRequestedAmount(), o.getAllocatedAmount(), o.getStatus().name()))
                .toList();
    }
}
