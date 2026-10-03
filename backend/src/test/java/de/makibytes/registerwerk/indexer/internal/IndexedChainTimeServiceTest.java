package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("IndexedChainTimeService: block-time evidence per deployment (H7)")
class IndexedChainTimeServiceTest {

    private final ChainConfigRepository chains = mock(ChainConfigRepository.class);
    private final IndexerStateRepository states = mock(IndexerStateRepository.class);
    private final IndexedChainTimeService service = new IndexedChainTimeService(chains, states);

    private AssetDeployment deployment(UUID chainId, AssetDeployment.DeploymentStatus status) {
        AssetDeployment d = new AssetDeployment();
        d.setId(UUID.randomUUID());
        d.setChainConfigId(chainId);
        d.setDeploymentStatus(status);
        return d;
    }

    private UUID evmChain(Instant blockTime) {
        UUID id = UUID.randomUUID();
        ChainConfig chain = mock(ChainConfig.class);
        when(chain.getId()).thenReturn(id);
        when(chain.getChainType()).thenReturn(ChainConfig.ChainType.EVM);
        when(chains.findById(id)).thenReturn(Optional.of(chain));
        IndexerState state = new IndexerState();
        state.setLastSyncedBlockTime(blockTime);
        when(states.findByChainConfigIdAndIndexerType(id, IndexerState.IndexerType.GRAPH_NODE))
                .thenReturn(Optional.of(state));
        return id;
    }

    @Test
    @DisplayName("one EVM deployment: the block time its indexer has processed")
    void evmDeployment() {
        Instant t = Instant.parse("2025-06-28T01:00:00Z");
        UUID chain = evmChain(t);

        assertThat(service.indexedThrough(List.of(deployment(chain, AssetDeployment.DeploymentStatus.CONFIRMED))))
                .contains(t);
    }

    @Test
    @DisplayName("several deployments: the EARLIEST block time (every chain must have been indexed past the cut-off)")
    void earliestOfSeveral() {
        Instant early = Instant.parse("2025-06-27T20:00:00Z");
        Instant late = Instant.parse("2025-06-28T02:00:00Z");

        assertThat(service.indexedThrough(List.of(
                deployment(evmChain(late), AssetDeployment.DeploymentStatus.CONFIRMED),
                deployment(evmChain(early), AssetDeployment.DeploymentStatus.CONFIRMED)))).contains(early);
    }

    @Test
    @DisplayName("an indexer that records no block time (or a non-EVM chain) yields NO evidence - never 'fresh'")
    void missingEvidence() {
        UUID noTime = evmChain(null);
        UUID other = UUID.randomUUID();
        ChainConfig solana = mock(ChainConfig.class);
        when(solana.getChainType()).thenReturn(ChainConfig.ChainType.SOLANA);
        when(chains.findById(other)).thenReturn(Optional.of(solana));

        assertThat(service.indexedThrough(List.of(deployment(noTime, AssetDeployment.DeploymentStatus.CONFIRMED)))).isEmpty();
        assertThat(service.indexedThrough(List.of(deployment(other, AssetDeployment.DeploymentStatus.CONFIRMED)))).isEmpty();
        assertThat(service.indexedThrough(List.of(
                deployment(evmChain(Instant.now()), AssetDeployment.DeploymentStatus.CONFIRMED),
                deployment(other, AssetDeployment.DeploymentStatus.CONFIRMED)))).isEmpty();
    }

    @Test
    @DisplayName("FAILED deployments never held a token and are ignored; no live deployment means no evidence")
    void failedDeploymentsIgnored() {
        Instant t = Instant.parse("2025-06-28T01:00:00Z");
        assertThat(service.indexedThrough(List.of(
                deployment(evmChain(t), AssetDeployment.DeploymentStatus.CONFIRMED),
                deployment(UUID.randomUUID(), AssetDeployment.DeploymentStatus.FAILED)))).contains(t);
        assertThat(service.indexedThrough(List.of(
                deployment(UUID.randomUUID(), AssetDeployment.DeploymentStatus.FAILED)))).isEmpty();
    }
}
