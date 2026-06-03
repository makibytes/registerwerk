package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.blockchain.api.CantonBondOperations;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Routes corporate-action settlement to the appropriate blockchain adapter.
 * Lives in {@code corporateactions/internal} so it can access both
 * {@link CantonBondOperations} from {@code blockchain.api} and
 * {@link CorporateActionRepository} — without introducing a new Modulith cycle
 * (corporateactions already depends on asset and deployment; blockchain does not
 * depend on corporateactions, so adding this direction is safe).
 *
 * Token standard is resolved via a native cross-table SQL query on
 * {@link CorporateActionRepository} to avoid importing from the asset module
 * at the Java level (which would close an additional cycle).
 */
@Component
class CorporateActionSettlementListener {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionSettlementListener.class);

    private final CorporateActionRepository corporateActionRepository;
    private final CantonBondOperations cantonBondOperations;

    CorporateActionSettlementListener(
            CorporateActionRepository corporateActionRepository,
            CantonBondOperations cantonBondOperations) {
        this.corporateActionRepository = corporateActionRepository;
        this.cantonBondOperations = cantonBondOperations;
    }

    @Async
    @EventListener
    public void onSettlementRequested(CorporateActionSettlementRequestedEvent event) {
        Optional<CorporateAction> caOpt = corporateActionRepository.findById(event.corporateActionId());
        if (caOpt.isEmpty()) {
            log.warn("CorporateAction not found for settlement: id={}", event.corporateActionId());
            return;
        }

        // Resolve token standard via native cross-table query (no Java import of asset.api required).
        String standard = corporateActionRepository.findTokenStandardByCorpAction(event.corporateActionId());

        CorporateAction ca = caOpt.get();
        log.info("Settlement dispatch: corporateActionId={} actionType={} standard={}",
                ca.getId(), ca.getActionType(), standard);

        if (standard == null) {
            log.warn("Token standard unknown for assetId={}; settlement remains AWAITING_SETTLEMENT.", ca.getAssetId());
            return;
        }

        switch (standard) {
            case "DAML_BOND_FIXED", "DAML_BOND_FLOATING", "DAML_BOND_ZERO" ->
                dispatchCantonCoupon(ca);

            case "ERC3525" ->
                log.info("ERC-3525 coupon settlement for assetId={} requires on-chain coupon distributor — " +
                         "action remains AWAITING_SETTLEMENT for operator review.", ca.getAssetId());

            case "ERC4626", "ERC7540" ->
                log.info("ERC-4626/7540 vault distribution for assetId={} requires NAV strike — " +
                         "action remains AWAITING_SETTLEMENT for operator review.", ca.getAssetId());

            default ->
                log.info("No automated settlement adapter for standard={}. " +
                         "corporateActionId={} remains AWAITING_SETTLEMENT.", standard, ca.getId());
        }
    }

    private void dispatchCantonCoupon(CorporateAction ca) {
        if (ca.getAmountPerUnit() == null || ca.getPaymentDate() == null) {
            log.warn("Canton coupon missing amountPerUnit or paymentDate: id={}", ca.getId());
            return;
        }
        Instant paymentInstant = ca.getPaymentDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        cantonBondOperations.payCoupon(ca.getId(), paymentInstant, ca.getAmountPerUnit(), null)
                .thenAccept(txHash -> {
                    ca.setStatus(CorporateAction.Status.SETTLED);
                    ca.setSettlementTxHash(txHash);
                    corporateActionRepository.save(ca);
                    log.info("Canton coupon settled: corporateActionId={} txHash={}", ca.getId(), txHash);
                })
                .exceptionally(ex -> {
                    log.error("Canton coupon settlement failed: corporateActionId={}", ca.getId(), ex);
                    return null;
                });
    }
}
