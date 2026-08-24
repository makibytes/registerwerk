package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.blockchain.api.CantonBondOperations;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementRequestedEvent;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.chain.api.Chain;
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
 * Verifies : previously EVERY DAML bond corporate action (coupon or
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
        deployment.setChain(Chain.CANTON);
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        deployment.setContractAddress("00canton-contract-id");
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
    @DisplayName("PARTIAL_REDEMPTION never exercises terminal Redeem and stays awaiting manual settlement")
    void partialRedemption_doesNotArchiveTheWholeBond() {
        actionOfType(CorporateAction.ActionType.PARTIAL_REDEMPTION);

        listener.onSettlementRequested(new CorporateActionSettlementRequestedEvent(
                corporateActionId, assetId, CorporateAction.ActionType.PARTIAL_REDEMPTION));

        verify(cantonBondOperations, never()).redeem(any(), any(), any());
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
    @DisplayName("DIVIDEND is not silently reinterpreted as a bond coupon")
    void dividend_doesNotRouteToPayCoupon() {
        actionOfType(CorporateAction.ActionType.DIVIDEND);

        listener.onSettlementRequested(new CorporateActionSettlementRequestedEvent(
                corporateActionId, assetId, CorporateAction.ActionType.DIVIDEND));

        verify(cantonBondOperations, never()).payCoupon(any(), any(), any(), any());
        verify(cantonBondOperations, never()).redeem(any(), any(), any());
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
    @DisplayName("SPLIT has no Canton lifecycle mapping — no on-chain split primitive exists on any "
            + "supported standard, so it dispatches nothing and always settles via the operator's mark-settled")
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

    @Test
    @DisplayName("an unrelated EVM deployment is never selected for a Canton exercise")
    void selectsOnlyConfirmedCantonDeployment() {
        actionOfType(CorporateAction.ActionType.REDEMPTION);
        AssetDeployment evm = new AssetDeployment();
        ReflectionTestUtils.setField(evm, "id", UUID.randomUUID());
        evm.setChain(Chain.ETHEREUM);
        evm.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        evm.setContractAddress("0x0000000000000000000000000000000000000001");
        AssetDeployment canton = new AssetDeployment();
        ReflectionTestUtils.setField(canton, "id", deploymentId);
        canton.setChain(Chain.CANTON);
        canton.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        canton.setContractAddress("00canton-contract-id");
        when(assetDeploymentRepository.findByAssetId(assetId)).thenReturn(List.of(evm, canton));
        when(cantonBondOperations.redeem(eq(deploymentId), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("tx-redeem"));

        listener.onSettlementRequested(new CorporateActionSettlementRequestedEvent(
                corporateActionId, assetId, CorporateAction.ActionType.REDEMPTION));

        verify(cantonBondOperations, timeout(1000)).redeem(eq(deploymentId), any(), any());
    }
}
