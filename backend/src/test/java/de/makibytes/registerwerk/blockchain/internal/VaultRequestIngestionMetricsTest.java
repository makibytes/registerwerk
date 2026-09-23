package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.EvmFinalityResolver;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.VaultRequestRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthLog;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Veto note 1: an RPC outage must be visible in the ingestion metrics. Before the fix a failing
 * deployment contributed 0 to the lag gauge and nothing else was exported, so a total outage
 * read as "caught up" and {@code VaultRequestIngestionLagging} could never fire.
 */
@DisplayName("VaultRequestIngestionService — lag/failure metrics under RPC failure")
class VaultRequestIngestionMetricsTest {

    private final AssetLookupPort assetLookupPort = mock(AssetLookupPort.class);
    private final AssetDeploymentRepository deploymentRepository = mock(AssetDeploymentRepository.class);
    private final ChainConfigRepository chainConfigRepository = mock(ChainConfigRepository.class);
    private final EvmContractService evmContractService = mock(EvmContractService.class);
    private final EvmFinalityResolver finalityResolver = mock(EvmFinalityResolver.class);
    private final VaultRequestIngestCursorRepository cursorRepository = mock(VaultRequestIngestCursorRepository.class);
    private final IsolatedTransactionExecutor isolated = mock(IsolatedTransactionExecutor.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Web3j web3j = mock(Web3j.class);

    private VaultRequestIngestionService service;
    private AssetDeployment dep;
    private VaultRequestIngestCursor cursor;

    @BeforeEach
    void setUp() throws Exception {
        UUID assetId = UUID.randomUUID();
        UUID chainId = UUID.randomUUID();
        AssetLookupPort.AssetInfo asset = mock(AssetLookupPort.AssetInfo.class);
        when(asset.id()).thenReturn(assetId);
        when(asset.tokenStandard()).thenReturn(TokenStandard.ERC7540);
        when(assetLookupPort.findAll()).thenReturn(List.of(asset));

        dep = mock(AssetDeployment.class);
        UUID depId = UUID.randomUUID();
        when(dep.getId()).thenReturn(depId);
        when(dep.getAssetId()).thenReturn(assetId);
        when(dep.getDeploymentStatus()).thenReturn(AssetDeployment.DeploymentStatus.CONFIRMED);
        when(dep.getContractAddress()).thenReturn("0x00000000000000000000000000000000000000aa");
        when(dep.getChainConfigId()).thenReturn(chainId);
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(dep));

        ChainConfig chain = mock(ChainConfig.class);
        when(chain.getId()).thenReturn(chainId);
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(evmContractService.evmClient(chainId)).thenReturn(web3j);

        cursor = new VaultRequestIngestCursor(depId, 1_000);
        when(cursorRepository.findById(depId)).thenAnswer(inv -> Optional.of(cursor));
        when(cursorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            inv.<IsolatedTransactionExecutor.Work>getArgument(0).run();
            return null;
        }).when(isolated).run(any());

        service = new VaultRequestIngestionService(assetLookupPort, deploymentRepository, chainConfigRepository,
                evmContractService, finalityResolver, mock(VaultRequestRepository.class), cursorRepository,
                mock(ChainEffectRecorder.class), isolated, registry);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    @DisplayName("eth_getLogs failing keeps the lag at last-known head − cursor and flags the deployment")
    void failingRpcReportsLagAndFailure() throws Exception {
        long head = 1_000 + VaultRequestIngestionService.MAX_BLOCK_RANGE * 30L;
        when(finalityResolver.finalizedHead(any(), eq(web3j))).thenReturn(Optional.of(head));
        @SuppressWarnings("unchecked")
        Request<?, EthLog> logsRequest = mock(Request.class);
        when(logsRequest.send()).thenThrow(new IOException("connection refused"));
        doReturn(logsRequest).when(web3j).ethGetLogs(any());

        service.ingestAll();

        assertThat(gauge("registerwerk_vault_request_ingest_lag_blocks")).isEqualTo((double) (head - 1_000));
        assertThat(gauge("registerwerk_vault_request_ingest_failing_deployments")).isEqualTo(1.0);
        assertThat(registry.get("registerwerk_vault_request_ingest_errors_total").counter().count()).isEqualTo(1.0);

        // Head itself unreadable now: the lag stays at the last-known value rather than dropping to 0.
        when(finalityResolver.finalizedHead(any(), eq(web3j))).thenThrow(new IOException("rpc down"));
        service.ingestAll();

        assertThat(gauge("registerwerk_vault_request_ingest_lag_blocks")).isEqualTo((double) (head - 1_000));
        assertThat(gauge("registerwerk_vault_request_ingest_failing_deployments")).isEqualTo(1.0);
        assertThat(registry.get("registerwerk_vault_request_ingest_errors_total").counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a caught-up vault whose RPC goes down still raises the failing-deployments gauge")
    void outageOnCaughtUpVaultIsVisible() throws Exception {
        when(finalityResolver.finalizedHead(any(), eq(web3j))).thenThrow(new IOException("rpc down"));

        service.ingestAll();

        assertThat(gauge("registerwerk_vault_request_ingest_lag_blocks")).isZero();
        assertThat(gauge("registerwerk_vault_request_ingest_failing_deployments")).isEqualTo(1.0);
    }
}
