package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.IndexingCoverage.Coverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("IndexingCoverageService — P4-01 fail-closed ingestion evidence")
class IndexingCoverageServiceTest {

    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private IndexerStateRepository indexerStateRepository;
    @Mock private SolanaMintSyncCursorRepository solanaMintSyncCursorRepository;

    private IndexingCoverageService service;
    private final UUID chainId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new IndexingCoverageService(chainConfigRepository, indexerStateRepository,
                solanaMintSyncCursorRepository, true, Duration.ofHours(2));
    }

    private AssetDeployment deployment() {
        AssetDeployment d = new AssetDeployment();
        d.setId(UUID.randomUUID());
        d.setContractAddress("0xToken");
        d.setChainConfigId(chainId);
        d.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        return d;
    }

    private ChainConfig chain(ChainConfig.ChainType type, String graphUrl) {
        ChainConfig c = new ChainConfig();
        c.setId(chainId);
        c.setIdentifier("CHAIN_X");
        ReflectionTestUtils.setField(c, "chainType", type);
        c.setGraphNodeUrl(graphUrl);
        lenient().when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(c));
        return c;
    }

    private void state(IndexerState.IndexerType type, IndexerState.IndexerStatus status, Instant syncedAt) {
        IndexerState s = new IndexerState();
        s.setChainConfigId(chainId);
        s.setIndexerType(type);
        s.setStatus(status);
        s.setLastSyncedAt(syncedAt);
        lenient().when(indexerStateRepository.findByChainConfigIdAndIndexerType(chainId, type))
                .thenReturn(Optional.of(s));
    }

    @Test
    @DisplayName("EVM chain without a Graph Node is NOT covered")
    void evmWithoutGraphNode() {
        chain(ChainConfig.ChainType.EVM, null);
        Coverage c = service.evaluate(deployment());
        assertThat(c.covered()).isFalse();
        assertThat(c.reason()).contains("no Graph Node");
    }

    @Test
    @DisplayName("EVM chain with Graph Node and a fresh ACTIVE indexer is covered")
    void evmFreshActive() {
        chain(ChainConfig.ChainType.EVM, "http://graph");
        state(IndexerState.IndexerType.GRAPH_NODE, IndexerState.IndexerStatus.ACTIVE, Instant.now().minusSeconds(60));
        assertThat(service.evaluate(deployment()).covered()).isTrue();
    }

    @Test
    @DisplayName("a stale, errored or never-run indexer is NOT covered")
    void staleErroredOrMissing() {
        chain(ChainConfig.ChainType.EVM, "http://graph");
        assertThat(service.evaluate(deployment()).reason()).contains("never ran");
        state(IndexerState.IndexerType.GRAPH_NODE, IndexerState.IndexerStatus.ACTIVE, Instant.now().minus(Duration.ofHours(5)));
        assertThat(service.evaluate(deployment()).reason()).contains("stale");
        state(IndexerState.IndexerType.GRAPH_NODE, IndexerState.IndexerStatus.ERROR, Instant.now());
        assertThat(service.evaluate(deployment()).reason()).contains("ERROR");
    }

    @Test
    @DisplayName("Solana is partial (plain SPL transfers); Canton needs a live stream, Stellar stays partial")
    void solanaCantonStellar() {
        chain(ChainConfig.ChainType.SOLANA, null);
        state(IndexerState.IndexerType.SOLANA_POLL, IndexerState.IndexerStatus.ACTIVE, Instant.now());
        // B5: plain SPL transfers are invisible to the mint-address signature listing -> never COVERED.
        Coverage solana = service.evaluate(deployment());
        assertThat(solana.covered()).isFalse();
        assertThat(solana.reason()).contains("plain SPL transfers not observable");

        // Canton is covered from a live CANTON_STREAM state (K2 links deployment ids).
        chain(ChainConfig.ChainType.CANTON, null);
        assertThat(service.evaluate(deployment()).reason()).contains("never ran");
        state(IndexerState.IndexerType.CANTON_STREAM, IndexerState.IndexerStatus.ACTIVE, Instant.now());
        assertThat(service.evaluate(deployment()).covered()).isTrue();

        // Stellar Stage 1 is honest about what it cannot see, even with a fresh indexer state.
        chain(ChainConfig.ChainType.STELLAR, null);
        state(IndexerState.IndexerType.STELLAR_HORIZON, IndexerState.IndexerStatus.ACTIVE, Instant.now());
        Coverage stellar = service.evaluate(deployment());
        assertThat(stellar.covered()).isFalse();
        assertThat(stellar.reason()).contains("holder-to-holder transfers not observable");
    }

    @Test
    @DisplayName("a deployment without a chain configuration is NOT covered; enforce=false covers everything")
    void noChainAndSwitch() {
        AssetDeployment d = deployment();
        d.setChainConfigId(null);
        assertThat(service.evaluate(d).covered()).isFalse();
        IndexingCoverageService relaxed = new IndexingCoverageService(chainConfigRepository, indexerStateRepository,
                solanaMintSyncCursorRepository, false, Duration.ofHours(2));
        assertThat(relaxed.evaluate(d).covered()).isTrue();
    }
}
