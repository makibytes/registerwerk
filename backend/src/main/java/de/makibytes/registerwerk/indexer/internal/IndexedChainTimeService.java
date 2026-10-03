package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.indexer.api.IndexedChainTime;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * Block-time evidence from {@code indexer_state.last_synced_block_time}, written by the Graph Node (EVM) indexer on
 * every successful tick. Other chain types record none, so a deployment on one of them yields no evidence and the
 * freshness gate falls back to its wall-clock rule for that asset (documented limitation, not silently "fresh").
 */
@Component
class IndexedChainTimeService implements IndexedChainTime {

    private final ChainConfigRepository chainConfigRepository;
    private final IndexerStateRepository indexerStateRepository;

    IndexedChainTimeService(ChainConfigRepository chainConfigRepository, IndexerStateRepository indexerStateRepository) {
        this.chainConfigRepository = chainConfigRepository;
        this.indexerStateRepository = indexerStateRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> indexedThrough(Collection<AssetDeployment> deployments) {
        Instant earliest = null;
        boolean any = false;
        for (AssetDeployment deployment : deployments) {
            if (deployment.getDeploymentStatus() == AssetDeployment.DeploymentStatus.FAILED) {
                continue;
            }
            any = true;
            Instant evidence = evidenceFor(deployment);
            if (evidence == null) {
                return Optional.empty();
            }
            if (earliest == null || evidence.isBefore(earliest)) {
                earliest = evidence;
            }
        }
        return any ? Optional.ofNullable(earliest) : Optional.empty();
    }

    private Instant evidenceFor(AssetDeployment deployment) {
        if (deployment.getChainConfigId() == null) {
            return null;
        }
        Optional<ChainConfig> chain = chainConfigRepository.findById(deployment.getChainConfigId());
        if (chain.isEmpty() || chain.get().getChainType() != ChainConfig.ChainType.EVM) {
            return null;
        }
        return indexerStateRepository
                .findByChainConfigIdAndIndexerType(chain.get().getId(), IndexerState.IndexerType.GRAPH_NODE)
                .map(IndexerState::getLastSyncedBlockTime)
                .orElse(null);
    }
}
