package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.blockchain.api.CantonBondOperations;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementRequestedEvent;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final CantonBondOperations cantonBondOperations;
    private final CorporateActionSettlementWriter settlementWriter;

    CorporateActionSettlementListener(
            CorporateActionRepository corporateActionRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            CantonBondOperations cantonBondOperations,
            CorporateActionSettlementWriter settlementWriter) {
        this.corporateActionRepository = corporateActionRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.cantonBondOperations = cantonBondOperations;
        this.settlementWriter = settlementWriter;
    }

    /**
     * {@code @ApplicationModuleListener} defers dispatch until AFTER the publishing
     * transaction ({@code CorporateActionService.settle()}) commits. The prior plain
     * {@code @Async @EventListener} dispatched at publish time, so this handler's
     * {@code findById} could run before the row was visible and silently drop the
     * settlement ("CorporateAction not found"). See {@code audit.internal.AuditEventRecorder}
     * for the same pattern applied elsewhere.
     */
    @ApplicationModuleListener
    void onSettlementRequested(CorporateActionSettlementRequestedEvent event) {
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
                dispatchCanton(ca);

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

    /**
     * Dispatches a Canton bond corporate action by {@link CorporateAction.ActionType}, not just
     * token standard: COUPON routes to {@code payCoupon}, REDEMPTION exercises {@code Redeem} on
     * the DAML ledger and retires the position — routing both to {@code payCoupon} would pay a
     * matured bond's face value without ever retiring it. SPLIT/REVERSE_SPLIT/CONVERSION/
     * CAPITAL_CALL have no Canton lifecycle-choice equivalent and fall through to "no automated
     * adapter" like the other unsupported standards above — this is deliberate for SPLIT (no
     * supported token standard has an on-chain split primitive; it always settles via the
     * operator's manual {@code mark-settled}), not an oversight.
     */
    private void dispatchCanton(CorporateAction ca) {
        Optional<AssetDeployment> deployment = resolveCantonDeployment(ca.getAssetId());
        if (deployment.isEmpty()) {
            log.warn("No Canton deployment found for assetId={}; settlement remains AWAITING_SETTLEMENT.",
                    ca.getAssetId());
            return;
        }
        UUID deploymentId = deployment.get().getId();

        switch (ca.getActionType()) {
            case COUPON, DIVIDEND, INTEREST_PAYMENT -> dispatchCantonCoupon(ca, deploymentId);
            case REDEMPTION, PARTIAL_REDEMPTION -> dispatchCantonRedemption(ca, deploymentId);
            case CALL -> dispatchCantonEarlyCall(ca, deploymentId);
            default -> log.info("No Canton lifecycle choice mapped for actionType={}. " +
                            "corporateActionId={} remains AWAITING_SETTLEMENT for operator review.",
                    ca.getActionType(), ca.getId());
        }
    }

    private Optional<AssetDeployment> resolveCantonDeployment(UUID assetId) {
        List<AssetDeployment> deployments = assetDeploymentRepository.findByAssetId(assetId);
        return deployments.stream().findFirst();
    }

    private void dispatchCantonCoupon(CorporateAction ca, UUID deploymentId) {
        if (ca.getAmountPerUnit() == null || ca.getPaymentDate() == null) {
            log.warn("Canton coupon missing amountPerUnit or paymentDate: id={}", ca.getId());
            return;
        }
        Instant paymentInstant = ca.getPaymentDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        UUID corporateActionId = ca.getId();
        cantonBondOperations.payCoupon(deploymentId, paymentInstant, ca.getAmountPerUnit(), null)
                .thenAccept(txHash -> {
                    settlementWriter.markSettled(corporateActionId, txHash);
                    log.info("Canton coupon settled: corporateActionId={} txHash={}", corporateActionId, txHash);
                })
                .exceptionally(ex -> {
                    log.error("Canton coupon settlement failed: corporateActionId={}", corporateActionId, ex);
                    return null;
                });
    }

    private void dispatchCantonRedemption(CorporateAction ca, UUID deploymentId) {
        if (ca.getPaymentDate() == null) {
            log.warn("Canton redemption missing paymentDate: id={}", ca.getId());
            return;
        }
        Instant maturityInstant = ca.getPaymentDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        UUID corporateActionId = ca.getId();
        cantonBondOperations.redeem(deploymentId, maturityInstant, null)
                .thenAccept(txHash -> {
                    settlementWriter.markSettled(corporateActionId, txHash);
                    log.info("Canton bond redeemed: corporateActionId={} deploymentId={} txHash={}",
                            corporateActionId, deploymentId, txHash);
                })
                .exceptionally(ex -> {
                    log.error("Canton redemption failed: corporateActionId={}", corporateActionId, ex);
                    return null;
                });
    }

    private void dispatchCantonEarlyCall(CorporateAction ca, UUID deploymentId) {
        if (ca.getAmountPerUnit() == null || ca.getPaymentDate() == null) {
            log.warn("Canton early call missing amountPerUnit (call price) or paymentDate: id={}", ca.getId());
            return;
        }
        Instant callInstant = ca.getPaymentDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        UUID corporateActionId = ca.getId();
        cantonBondOperations.earlyCall(deploymentId, callInstant, ca.getAmountPerUnit(), null)
                .thenAccept(txHash -> {
                    settlementWriter.markSettled(corporateActionId, txHash);
                    log.info("Canton bond called: corporateActionId={} deploymentId={} txHash={}",
                            corporateActionId, deploymentId, txHash);
                })
                .exceptionally(ex -> {
                    log.error("Canton early call failed: corporateActionId={}", corporateActionId, ex);
                    return null;
                });
    }
}
