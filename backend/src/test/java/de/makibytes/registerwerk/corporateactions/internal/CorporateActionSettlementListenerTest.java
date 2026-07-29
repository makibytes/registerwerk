package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.blockchain.api.CantonBondOperations;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementRequestedEvent;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies Phase 6 finding #1: previously EVERY DAML bond corporate action (coupon or
 * redemption alike) was routed to {@code payCoupon}, so a REDEMPTION action paid face value
 * but never exercised {@code Redeem} on the DAML ledger. Also verifies the pre-existing
 * (separately discovered while fixing #1) bug where the wrong ID — the corporate action's, not
 * the asset deployment's — was passed to {@code CantonBondOperations}, which would fail to
 * resolve the deployment in the real (canton-profile) implementation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorporateActionSettlementListener Canton dispatch-routing unit tests")
class CorporateActionSettlementListenerTest {

    @Mock private CorporateActionRepository corporateActionRepository;
    @Mock private AssetDeploymentRepository assetDeploymentRepository;
    @Mock private CantonBondOperations cantonBondOperations;
    @Mock private CorporateActionSettlementWriter settlementWriter;

    private CorporateActionSettlementListener listener;

    private final UUID corporateActionId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new CorporateActionSettlementListener(
                corporateActionRepository, assetDeploymentRepository, cantonBondOperations, settlementWriter);

        AssetDeployment deployment = new AssetDeployment();
        ReflectionTestUtils.setField(deployment, "id", deploymentId);
        when(assetDeploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment));
        when(corporateActionRepository.findTokenStandardByCorpAction(corporateActionId)).thenReturn("DAML_BOND_FIXED");
    }

    private CorporateAction actionOfType(CorporateAction.ActionType type) {
        CorporateAction ca = new CorporateAction();
        ReflectionTestUtils.setField(ca, "id", corporateActionId);
        ca.setAssetId(assetId);
        ca.setActionType(type);
        ca.setAmountPerUnit(new BigDecimal("0.05"));
        ca.setPaymentDate(LocalDate.now());
        when(corporateActionRepository.findById(corporateActionId)).thenReturn(Optional.of(ca));
        return ca;
    }

    @Test
    @DisplayName("REDEMPTION routes to CantonBondOperations.redeem (not payCoupon) with the real deploymentId")
    void redemption_routesToRedeem_withRealDeploymentId() {
        actionOfType(CorporateAction.ActionType.REDEMPTION);
        when(cantonBondOperations.redeem(eq(deploymentId), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("tx-redeem-1"));

        listener.onSettlementRequested(new CorporateActionSettlementRequestedEvent(
                corporateActionId, assetId, CorporateAction.ActionType.REDEMPTION));

        verify(cantonBondOperations, timeout(1000)).redeem(eq(deploymentId), any(), any());
        verify(cantonBondOperations, never()).payCoupon(any(), any(), any(), any());
        verify(settlementWriter, timeout(1000)).markSettled(corporateActionId, "tx-redeem-1");
    }

    @Test
    @DisplayName("PARTIAL_REDEMPTION also routes to redeem, not payCoupon")
    void partialRedemption_routesToRedeem() {
        actionOfType(CorporateAction.ActionType.PARTIAL_REDEMPTION);
        when(cantonBondOperations.redeem(eq(deploymentId), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("tx-redeem-2"));

        listener.onSettlementRequested(new CorporateActionSettlementRequestedEvent(
                corporateActionId, assetId, CorporateAction.ActionType.PARTIAL_REDEMPTION));

        verify(cantonBondOperations, timeout(1000)).redeem(eq(deploymentId), any(), any());
        verify(cantonBondOperations, never()).payCoupon(any(), any(), any(), any());
    }

    @Test
    @DisplayName("COUPON still routes to payCoupon, with the real deploymentId (not the corporateActionId)")
    void coupon_routesToPayCoupon_withRealDeploymentId() {
        actionOfType(CorporateAction.ActionType.COUPON);
        when(cantonBondOperations.payCoupon(eq(deploymentId), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("tx-coupon-1"));

        listener.onSettlementRequested(new CorporateActionSettlementRequestedEvent(
                corporateActionId, assetId, CorporateAction.ActionType.COUPON));

        verify(cantonBondOperations, timeout(1000)).payCoupon(eq(deploymentId), any(), any(), any());
        verify(cantonBondOperations, never()).redeem(any(), any(), any());
        verify(settlementWriter, timeout(1000)).markSettled(corporateActionId, "tx-coupon-1");
    }

    @Test
    @DisplayName("CALL routes to earlyCall using amountPerUnit as the call price")
    void call_routesToEarlyCall() {
        actionOfType(CorporateAction.ActionType.CALL);
        when(cantonBondOperations.earlyCall(eq(deploymentId), any(), eq(new BigDecimal("0.05")), any()))
                .thenReturn(CompletableFuture.completedFuture("tx-call-1"));

        listener.onSettlementRequested(new CorporateActionSettlementRequestedEvent(
                corporateActionId, assetId, CorporateAction.ActionType.CALL));

        verify(cantonBondOperations, timeout(1000)).earlyCall(eq(deploymentId), any(), eq(new BigDecimal("0.05")), any());
    }

    @Test
    @DisplayName("an action type with no Canton lifecycle mapping (e.g. SPLIT) dispatches nothing and stays AWAITING_SETTLEMENT")
    void unmappedActionType_dispatchesNothing() {
        actionOfType(CorporateAction.ActionType.SPLIT);

        listener.onSettlementRequested(new CorporateActionSettlementRequestedEvent(
                corporateActionId, assetId, CorporateAction.ActionType.SPLIT));

        verify(cantonBondOperations, never()).redeem(any(), any(), any());
        verify(cantonBondOperations, never()).payCoupon(any(), any(), any(), any());
        verify(cantonBondOperations, never()).earlyCall(any(), any(), any(), any());
    }

    @Test
    @DisplayName("no Canton deployment found for the asset — dispatches nothing, remains AWAITING_SETTLEMENT")
    void noDeploymentFound_dispatchesNothing() {
        actionOfType(CorporateAction.ActionType.REDEMPTION);
        when(assetDeploymentRepository.findByAssetId(assetId)).thenReturn(List.of());

        listener.onSettlementRequested(new CorporateActionSettlementRequestedEvent(
                corporateActionId, assetId, CorporateAction.ActionType.REDEMPTION));

        verify(cantonBondOperations, never()).redeem(any(), any(), any());
    }
}
