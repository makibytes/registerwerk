package de.makibytes.registerwerk.blockchain.internal.confidential;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.ZamaRelayerClient;
import de.makibytes.registerwerk.blockchain.events.ConfidentialReconciliationCompletedEvent;
import de.makibytes.registerwerk.blockchain.internal.confidential.ConfidentialBalanceReconciliationService.HolderReconciliation;
import de.makibytes.registerwerk.blockchain.internal.confidential.ConfidentialBalanceReconciliationService.ReconciliationReport;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfidentialBalanceReconciliationService — register vs. decrypted on-chain balance")
class ConfidentialBalanceReconciliationServiceTest {

    @Mock private AssetLookupPort assetLookupPort;
    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private AssetHolderRepository holderRepository;
    @Mock private BlockchainClientRegistry clientRegistry;
    @Mock private EvmContractService evmContractService;
    @Mock private ZamaRelayerClient zamaRelayerClient;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ConfidentialBalanceReconciliationService service;
    private SimpleMeterRegistry meterRegistry;

    private static final UUID ASSET_ID = UUID.randomUUID();
    // ConfidentialERC20.decimals() = 6 — decrypted on-chain amounts are raw base units; register's
    // nominalAmount is already human-readable. Finding #3, Phase 9: reconcileOne must scale before
    // comparing, so test mocks must supply realistically-scaled raw values, not bare unit counts.
    private static final BigInteger SCALE = BigInteger.TEN.pow(6);

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ConfidentialBalanceReconciliationService(
                assetLookupPort, deploymentRepository, holderRepository,
                clientRegistry, evmContractService, zamaRelayerClient, eventPublisher, meterRegistry);
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private void stubConfidentialAsset(TokenStandard standard) {
        when(assetLookupPort.findById(ASSET_ID)).thenReturn(Optional.of(
                new AssetLookupPort.AssetInfo(ASSET_ID, "Confidential Asset", null, standard, null, null, null, "AST-100", null)));
    }

    private AssetDeployment confirmedDeployment() {
        AssetDeployment dep = new AssetDeployment();
        dep.setId(UUID.randomUUID());
        dep.setAssetId(ASSET_ID);
        dep.setChain(Chain.ETHEREUM);
        dep.setNetwork(Network.TESTNET);
        dep.setContractAddress("0x" + "a".repeat(40));
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        return dep;
    }

    private AssetHolder holder(BigDecimal nominalAmount) {
        AssetHolder h = new AssetHolder();
        h.setId(UUID.randomUUID());
        h.setAssetId(ASSET_ID);
        h.setInvestorId(UUID.randomUUID());
        h.setWalletAddress("0x" + "b".repeat(40));
        h.setNominalAmount(nominalAmount);
        return h;
    }

    @Test
    @DisplayName("rejects non-confidential token standards")
    void reconcile_rejectsNonConfidentialStandard() {
        stubConfidentialAsset(TokenStandard.ERC20);
        assertThatThrownBy(() -> service.reconcile(ASSET_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("requires a configured Zama relayer sidecar")
    void reconcile_requiresConfiguredRelayer() {
        stubConfidentialAsset(TokenStandard.CONF_ERC20);
        when(zamaRelayerClient.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service.reconcile(ASSET_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relayer");
    }

    @Test
    @DisplayName("throws when no confirmed on-chain deployment exists")
    void reconcile_noConfirmedDeployment_throws() {
        stubConfidentialAsset(TokenStandard.CONF_ERC20);
        when(zamaRelayerClient.isConfigured()).thenReturn(true);
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        assertThatThrownBy(() -> service.reconcile(ASSET_ID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("matching register/on-chain balances still publish a completed event with mismatchCount=0")
    void reconcile_allMatch_publishesCleanCompletedEvent() {
        stubConfidentialAsset(TokenStandard.CONF_ERC20);
        when(zamaRelayerClient.isConfigured()).thenReturn(true);
        AssetDeployment dep = confirmedDeployment();
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(dep));

        AssetHolder h = holder(BigDecimal.valueOf(1000));
        when(holderRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(h));

        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.call(any(), eq(dep.getContractAddress()), any()))
                .thenReturn(List.of((Type) new Uint256(BigInteger.valueOf(42))));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), eq(dep.getContractAddress())))
                .thenReturn(BigInteger.valueOf(1000).multiply(SCALE));

        ReconciliationReport report = service.reconcile(ASSET_ID);

        assertThat(report.allMatch()).isTrue();
        assertThat(report.holders()).hasSize(1);
        assertThat(report.holders().get(0).matches()).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(ConfidentialReconciliationCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().mismatchCount()).isZero();
        assertThat(captor.getValue().holderCount()).isEqualTo(1);
        // the 1-arg overload (no authenticated caller, e.g. the scheduled sweep) attributes to SYSTEM
        assertThat(captor.getValue().actorId()).isEqualTo(new UUID(0L, 0L));
        assertThat(captor.getValue().actorRole()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("a mismatched holder is reported and publishes an audit event with mismatchCount>0")
    void reconcile_mismatch_publishesEvent() {
        stubConfidentialAsset(TokenStandard.CONF_ERC3643);
        when(zamaRelayerClient.isConfigured()).thenReturn(true);
        AssetDeployment dep = confirmedDeployment();
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(dep));

        AssetHolder h = holder(BigDecimal.valueOf(1000));
        when(holderRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(h));

        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.call(any(), eq(dep.getContractAddress()), any()))
                .thenReturn(List.of((Type) new Uint256(BigInteger.valueOf(42))));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), eq(dep.getContractAddress())))
                .thenReturn(BigInteger.valueOf(999).multiply(SCALE)); // mismatch vs. register's 1000

        ReconciliationReport report = service.reconcile(ASSET_ID);

        assertThat(report.allMatch()).isFalse();
        HolderReconciliation result = report.holders().get(0);
        assertThat(result.matches()).isFalse();
        assertThat(result.onchainAmount()).isEqualTo(BigInteger.valueOf(999).multiply(SCALE));
        var captor = org.mockito.ArgumentCaptor.forClass(ConfidentialReconciliationCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().mismatchCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("actor/role passed to the 3-arg overload are recorded on the completed event")
    void reconcile_withActor_recordsActorOnEvent() {
        stubConfidentialAsset(TokenStandard.CONF_ERC20);
        when(zamaRelayerClient.isConfigured()).thenReturn(true);
        AssetDeployment dep = confirmedDeployment();
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(dep));

        AssetHolder h = holder(BigDecimal.valueOf(1000));
        when(holderRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(h));

        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.call(any(), eq(dep.getContractAddress()), any()))
                .thenReturn(List.of((Type) new Uint256(BigInteger.valueOf(42))));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), eq(dep.getContractAddress())))
                .thenReturn(BigInteger.valueOf(1000).multiply(SCALE));

        UUID actorId = UUID.randomUUID();
        service.reconcile(ASSET_ID, actorId, "REGISTRY_ADMIN");

        var captor = org.mockito.ArgumentCaptor.forClass(ConfidentialReconciliationCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().actorRole()).isEqualTo("REGISTRY_ADMIN");
    }

    @Test
    @DisplayName("a single holder's decrypt failure is recorded as an error, not thrown")
    void reconcile_holderDecryptFailure_recordedNotThrown() {
        stubConfidentialAsset(TokenStandard.CONF_ERC20);
        when(zamaRelayerClient.isConfigured()).thenReturn(true);
        AssetDeployment dep = confirmedDeployment();
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(dep));

        AssetHolder h = holder(BigDecimal.valueOf(500));
        when(holderRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(h));

        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.call(any(), eq(dep.getContractAddress()), any()))
                .thenThrow(new RuntimeException("eth_call error"));

        ReconciliationReport report = service.reconcile(ASSET_ID);

        assertThat(report.allMatch()).isFalse();
        HolderReconciliation result = report.holders().get(0);
        assertThat(result.matches()).isFalse();
        assertThat(result.error()).contains("eth_call error");
        assertThat(result.onchainAmount()).isNull();
    }

    @Test
    @DisplayName("finding #7: reconcileAll() skips the sweep entirely when the relayer isn't configured")
    void reconcileAll_skipsWhenRelayerNotConfigured() {
        when(zamaRelayerClient.isConfigured()).thenReturn(false);

        service.reconcileAll();

        verify(assetLookupPort, never()).findAll();
    }

    @Test
    @DisplayName("finding #7: reconcileAll() reconciles every confidential asset and skips non-confidential ones")
    void reconcileAll_reconcilesOnlyConfidentialAssets() {
        when(zamaRelayerClient.isConfigured()).thenReturn(true);

        UUID plainAssetId = UUID.randomUUID();
        var confAsset = new AssetLookupPort.AssetInfo(
                ASSET_ID, "Confidential Asset", null, TokenStandard.CONF_ERC20, null, null, null, "AST-100", null);
        var plainAsset = new AssetLookupPort.AssetInfo(
                plainAssetId, "Plain Asset", null, TokenStandard.ERC20, null, null, null, "AST-200", null);
        when(assetLookupPort.findAll()).thenReturn(List.of(confAsset, plainAsset));
        when(assetLookupPort.findById(ASSET_ID)).thenReturn(java.util.Optional.of(confAsset));

        AssetDeployment dep = confirmedDeployment();
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(dep));
        AssetHolder h = holder(BigDecimal.valueOf(1000));
        when(holderRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(h));
        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.call(any(), eq(dep.getContractAddress()), any()))
                .thenReturn(List.of((Type) new Uint256(BigInteger.valueOf(42))));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), eq(dep.getContractAddress())))
                .thenReturn(BigInteger.valueOf(1000).multiply(SCALE));

        service.reconcileAll();

        verify(deploymentRepository).findByAssetId(ASSET_ID);
        verify(deploymentRepository, never()).findByAssetId(plainAssetId);
        verify(eventPublisher).publishEvent(any(ConfidentialReconciliationCompletedEvent.class));
    }

    @Test
    @DisplayName("finding #7: reconcileAll() doesn't let one asset's failure abort the sweep")
    void reconcileAll_oneAssetFailureDoesNotAbortSweep() {
        when(zamaRelayerClient.isConfigured()).thenReturn(true);
        var confAsset = new AssetLookupPort.AssetInfo(
                ASSET_ID, "Confidential Asset", null, TokenStandard.CONF_ERC20, null, null, null, "AST-100", null);
        when(assetLookupPort.findAll()).thenReturn(List.of(confAsset));
        when(assetLookupPort.findById(ASSET_ID)).thenReturn(java.util.Optional.of(confAsset));
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of()); // no confirmed deployment -> reconcile() throws

        service.reconcileAll(); // must not propagate
    }

    @Test
    @DisplayName("ignores deployments that aren't CONFIRMED")
    void reconcile_ignoresNonConfirmedDeployment() {
        stubConfidentialAsset(TokenStandard.CONF_ERC20);
        when(zamaRelayerClient.isConfigured()).thenReturn(true);
        AssetDeployment pending = confirmedDeployment();
        pending.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(pending));

        assertThatThrownBy(() -> service.reconcile(ASSET_ID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("gauges reflect mismatch count and last-run timestamp after a run (repo-wide alerting follow-up)")
    void reconcile_updatesMismatchAndTimestampGauges() {
        stubConfidentialAsset(TokenStandard.CONF_ERC3643);
        when(zamaRelayerClient.isConfigured()).thenReturn(true);
        AssetDeployment dep = confirmedDeployment();
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(dep));

        AssetHolder h = holder(BigDecimal.valueOf(1000));
        when(holderRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(h));

        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.call(any(), eq(dep.getContractAddress()), any()))
                .thenReturn(List.of((Type) new Uint256(BigInteger.valueOf(42))));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), eq(dep.getContractAddress())))
                .thenReturn(BigInteger.valueOf(999).multiply(SCALE)); // mismatch vs. register's 1000

        assertThat(gauge("registerwerk_confidential_reconciliation_mismatch_total")).isZero();
        assertThat(gauge("registerwerk_confidential_reconciliation_last_run_timestamp_seconds")).isZero();

        long before = java.time.Instant.now().getEpochSecond();
        service.reconcile(ASSET_ID);

        assertThat(gauge("registerwerk_confidential_reconciliation_mismatch_total")).isEqualTo(1.0);
        assertThat(gauge("registerwerk_confidential_reconciliation_last_run_timestamp_seconds")).isGreaterThanOrEqualTo((double) before);
    }
}
