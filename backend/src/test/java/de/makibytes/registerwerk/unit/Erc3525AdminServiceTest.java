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

    private Erc3525AdminService service;

    private static final UUID DEPLOYMENT_ID = UUID.randomUUID();
    private static final UUID ASSET_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final BigInteger SLOT_ID = BigInteger.ONE;

    @BeforeEach
    void setUp() {
        service = new Erc3525AdminService(
                deploymentRepository, slotRepository, tokenUnitRepository, couponPaymentRepository,
                evmTransactions, txService, eventPublisher, starknetErc3525AdminService);
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
}
