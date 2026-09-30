package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent;
import de.makibytes.registerwerk.blockchain.internal.Erc3525AdminService;
import de.makibytes.registerwerk.blockchain.internal.deploy.StarknetErc3525AdminService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetSlot;
import de.makibytes.registerwerk.deployment.api.AssetSlotRepository;
import de.makibytes.registerwerk.deployment.api.AssetTokenUnitRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.abi.datatypes.Function;

import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Starknet-routing fix and audit-event fix,
 * — this service had no test coverage before either fix.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Erc3525AdminService — Starknet routing + audit events")
class Erc3525AdminServiceTest {

    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private AssetSlotRepository slotRepository;
    @Mock private AssetTokenUnitRepository tokenUnitRepository;
    @Mock private AssetCouponPaymentRepository couponPaymentRepository;
    @Mock private DurableEvmTransactionGateway evmTransactions;
    @Mock private BlockchainTransactionService txService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private StarknetErc3525AdminService starknetErc3525AdminService;
    @Mock private HolderBlockGate holderBlockGate;
    @Mock private de.makibytes.registerwerk.deployment.api.AssetLookupPort assetLookupPort;

    private Erc3525AdminService service;

    private static final UUID DEPLOYMENT_ID = UUID.randomUUID();
    private static final UUID ASSET_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final BigInteger SLOT_ID = BigInteger.ONE;

    @BeforeEach
    void setUp() {
        service = new Erc3525AdminService(
                deploymentRepository, slotRepository, tokenUnitRepository, couponPaymentRepository,
                evmTransactions, txService, eventPublisher, starknetErc3525AdminService, holderBlockGate,
                assetLookupPort, org.mockito.Mockito.mock(de.makibytes.registerwerk.kyc.api.OutboundDestinationGate.class));
    }

    private AssetDeployment deployment(Chain chain) {
        AssetDeployment dep = new AssetDeployment();
        dep.setId(DEPLOYMENT_ID);
        dep.setAssetId(ASSET_ID);
        dep.setChainConfigId(UUID.randomUUID());
        dep.setChain(chain);
        dep.setNetwork(Network.TESTNET);
        dep.setContractAddress("0xdeployed");
        return dep;
    }

    @Test
    @DisplayName("EVM: pauseSlot submits on-chain, publishes an audit event, and updates the slot")
    void pauseSlot_evm_submitsAndAudits() {
        AssetDeployment dep = deployment(Chain.ETHEREUM);
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(dep));
        AssetSlot slot = new AssetSlot();
        when(slotRepository.findByAssetIdAndSlotId(ASSET_ID, SLOT_ID)).thenReturn(Optional.of(slot));
        when(evmTransactions.submit(eq(dep.getChainConfigId()), eq("0xdeployed"),
                any(Function.class), any()))
                .thenReturn("0xtxhash");
        UUID expectedTxId = UUID.randomUUID();
        when(txService.record(eq("0xtxhash"), eq("pauseSlot"), eq(DEPLOYMENT_ID), eq(ASSET_ID),
                anyString(), anyString(), eq("0xdeployed"), any()))
                .thenReturn(expectedTxId);

        UUID result = service.pauseSlot(DEPLOYMENT_ID, SLOT_ID, ACTOR_ID, "REGISTRY_ADMIN");

        assertThat(result).isEqualTo(expectedTxId);
        assertThat(slot.isPaused()).isTrue();
        verify(starknetErc3525AdminService, never()).pauseSlot(any(), any());

        ArgumentCaptor<TokenAdminActionEvent> captor = ArgumentCaptor.forClass(TokenAdminActionEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().deploymentId()).isEqualTo(DEPLOYMENT_ID);
        assertThat(captor.getValue().methodName()).isEqualTo("pauseSlot");
        assertThat(captor.getValue().actorId()).isEqualTo(ACTOR_ID);
        assertThat(captor.getValue().actorRole()).isEqualTo("REGISTRY_ADMIN");
    }

    @Test
    @DisplayName("Starknet: pauseSlot routes to StarknetErc3525AdminService, not the EVM client, and still audits")
    void pauseSlot_starknet_routesToStarknetService() {
        AssetDeployment dep = deployment(Chain.STARKNET);
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(dep));
        when(starknetErc3525AdminService.pauseSlot(DEPLOYMENT_ID, SLOT_ID))
                .thenReturn(CompletableFuture.completedFuture("0xstarknettx"));
        UUID expectedTxId = UUID.randomUUID();
        when(txService.record(eq("0xstarknettx"), eq("pauseSlot"), eq(DEPLOYMENT_ID), eq(ASSET_ID),
                anyString(), anyString(), eq("0xdeployed"), any()))
                .thenReturn(expectedTxId);

        UUID result = service.pauseSlot(DEPLOYMENT_ID, SLOT_ID, ACTOR_ID, "REGISTRY_ADMIN");

        assertThat(result).isEqualTo(expectedTxId);
        verify(evmTransactions, never()).submit(any(), anyString(), any(Function.class), any());
        verify(eventPublisher).publishEvent(any(TokenAdminActionEvent.class));
    }

    @Test
    @DisplayName("Starknet: an EVM-only action (setSlotSupplyCap) throws a clear, specific error instead of a generic client-not-configured failure")
    void setSlotSupplyCap_starknet_throwsUnsupported() {
        AssetDeployment dep = deployment(Chain.STARKNET);
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(dep));

        assertThatThrownBy(() -> service.setSlotSupplyCap(DEPLOYMENT_ID, SLOT_ID, BigInteger.TEN, ACTOR_ID, "REGISTRY_ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("setSlotSupplyCap")
                .hasMessageContaining("Starknet");
        verify(evmTransactions, never()).submit(any(), anyString(), any(Function.class), any());
    }

    @Test
    @DisplayName("forcedValueTransfer on Starknet routes correctly and audits with the real actor")
    void forcedValueTransfer_starknet_routesAndAudits() {
        AssetDeployment dep = deployment(Chain.STARKNET);
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(dep));
        when(starknetErc3525AdminService.forcedValueTransfer(DEPLOYMENT_ID, BigInteger.ONE, BigInteger.TWO,
                BigInteger.TEN, "court order"))
                .thenReturn(CompletableFuture.completedFuture("0xftv"));
        when(txService.record(eq("0xftv"), eq("forcedTransferValue"), eq(DEPLOYMENT_ID), eq(ASSET_ID),
                anyString(), anyString(), eq("0xdeployed"), any()))
                .thenReturn(UUID.randomUUID());

        service.forcedValueTransfer(DEPLOYMENT_ID, BigInteger.ONE, BigInteger.TWO, BigInteger.TEN,
                "court order", ACTOR_ID, "REGISTRY_ADMIN");

        ArgumentCaptor<TokenAdminActionEvent> captor = ArgumentCaptor.forClass(TokenAdminActionEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().methodName()).isEqualTo("forcedTransferValue");
        assertThat(captor.getValue().actorId()).isEqualTo(ACTOR_ID);
    }

    // ── Holder-level controls (veto note 3: the compliant Cairo class requires whitelisted
    //    recipients, so the backend must be able to whitelist/freeze on Starknet) ──────────

    private static final String STARK_HOLDER =
            "0x049d36570d4e46f48e99674bd3fcc84644ddd6b96f7c741b1562b82f9e004dc7";
    private static final String EVM_HOLDER = "0x00000000000000000000000000000000000000a1";

    @Test
    @DisplayName("Starknet: whitelist/unwhitelist/freeze/unfreeze route to the Cairo admin service and audit")
    void holderControls_starknet_routeAndAudit() {
        AssetDeployment dep = deployment(Chain.STARKNET);
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(dep));
        when(starknetErc3525AdminService.whitelist(DEPLOYMENT_ID, STARK_HOLDER))
                .thenReturn(CompletableFuture.completedFuture("0xwl"));
        when(starknetErc3525AdminService.removeFromWhitelist(DEPLOYMENT_ID, STARK_HOLDER))
                .thenReturn(CompletableFuture.completedFuture("0xuwl"));
        when(starknetErc3525AdminService.freezeAddress(DEPLOYMENT_ID, STARK_HOLDER, "sanctions"))
                .thenReturn(CompletableFuture.completedFuture("0xfz"));
        when(starknetErc3525AdminService.unfreezeAddress(DEPLOYMENT_ID, STARK_HOLDER))
                .thenReturn(CompletableFuture.completedFuture("0xufz"));
        when(txService.record(anyString(), anyString(), eq(DEPLOYMENT_ID), eq(ASSET_ID),
                anyString(), anyString(), eq("0xdeployed"), any())).thenReturn(UUID.randomUUID());

        service.whitelistAddress(DEPLOYMENT_ID, STARK_HOLDER, ACTOR_ID, "REGISTRY_ADMIN");
        service.unwhitelistAddress(DEPLOYMENT_ID, STARK_HOLDER, ACTOR_ID, "REGISTRY_ADMIN");
        service.freezeAddress(DEPLOYMENT_ID, STARK_HOLDER, "sanctions", ACTOR_ID, "REGISTRY_ADMIN");
        service.unfreezeAddress(DEPLOYMENT_ID, STARK_HOLDER, ACTOR_ID, "REGISTRY_ADMIN");

        verify(txService).record(eq("0xwl"), eq("whitelist"), any(), any(), anyString(), anyString(), any(), any());
        verify(txService).record(eq("0xuwl"), eq("removeFromWhitelist"), any(), any(), anyString(), anyString(), any(), any());
        verify(txService).record(eq("0xfz"), eq("freezeAddress"), any(), any(), anyString(), anyString(), any(), any());
        verify(txService).record(eq("0xufz"), eq("unfreezeAddress"), any(), any(), anyString(), anyString(), any(), any());
        verify(eventPublisher, org.mockito.Mockito.times(4)).publishEvent(any(TokenAdminActionEvent.class));
        verify(evmTransactions, never()).submit(any(), anyString(), any(Function.class), any());
    }

    @Test
    @DisplayName("EVM: freezeAddress submits EwpgCompliance.freezeAddress(address,string)")
    void freezeAddress_evm_submits() {
        AssetDeployment dep = deployment(Chain.ETHEREUM);
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(dep));
        when(evmTransactions.submit(eq(dep.getChainConfigId()), eq("0xdeployed"), any(Function.class), any()))
                .thenReturn("0xtx");

        service.freezeAddress(DEPLOYMENT_ID, EVM_HOLDER, "sanctions", ACTOR_ID, "REGISTRY_ADMIN");

        ArgumentCaptor<Function> fn = ArgumentCaptor.forClass(Function.class);
        verify(evmTransactions).submit(any(), any(), fn.capture(), any());
        assertThat(fn.getValue().getName()).isEqualTo("freezeAddress");
        verify(starknetErc3525AdminService, never()).freezeAddress(any(), any(), any());
    }

    @Test
    @DisplayName("whitelisting a Sperrvermerk'd wallet or a malformed address is refused before any chain call")
    void whitelist_refusesBlockedOrMalformedAddress() {
        AssetDeployment dep = deployment(Chain.STARKNET);
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(dep));
        when(holderBlockGate.isBlocked(null, STARK_HOLDER)).thenReturn(true);

        assertThatThrownBy(() -> service.whitelistAddress(DEPLOYMENT_ID, STARK_HOLDER, ACTOR_ID, "REGISTRY_ADMIN"))
                .isInstanceOf(ComplianceGateException.class);
        assertThatThrownBy(() -> service.whitelistAddress(DEPLOYMENT_ID, "not-an-address", ACTOR_ID, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(starknetErc3525AdminService, never()).whitelist(any(), any());
    }

    @Test
    @DisplayName("EVM: a Starknet-length felt address is rejected for an EVM deployment")
    void freezeAddress_evm_rejectsFeltAddress() {
        AssetDeployment dep = deployment(Chain.ETHEREUM);
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(dep));

        assertThatThrownBy(() -> service.freezeAddress(DEPLOYMENT_ID, STARK_HOLDER, "x", ACTOR_ID, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(evmTransactions, never()).submit(any(), anyString(), any(Function.class), any());
    }

    @Test
    @DisplayName("Register freeze: mint, forced value transfer and force burn refuse while TRANSFER_PENDING")
    void holdingChangingOps_refusedWhileRegisterFrozen() {
        AssetDeployment dep = deployment(Chain.ETHEREUM);
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(dep));
        when(assetLookupPort.findById(ASSET_ID)).thenReturn(Optional.of(
                new de.makibytes.registerwerk.deployment.api.AssetLookupPort.AssetInfo(
                        ASSET_ID, "Bond", null, null, "ETHEREUM", "TESTNET", null, null, "TRANSFER_PENDING")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.mintIntoSlot(
                        DEPLOYMENT_ID, SLOT_ID, "0x00000000000000000000000000000000000000aa", BigInteger.TEN, ACTOR_ID, "REGISTRY_ADMIN"))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.forcedValueTransfer(
                        DEPLOYMENT_ID, BigInteger.ONE, BigInteger.TWO, BigInteger.TEN, "basis", ACTOR_ID, "REGISTRY_ADMIN"))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.forceBurnValue(
                        DEPLOYMENT_ID, BigInteger.ONE, BigInteger.TEN, "basis", ACTOR_ID, "REGISTRY_ADMIN"))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class);
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }
}
