package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.IndexingCoverage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link IndexingCoverage}: a deployment is covered only when its chain type has an
 * ingester that links transfers to deployments, the chain is configured for it, and the matching
 * {@code indexer_state} row is {@code ACTIVE} and synced within {@code max-staleness}.
 *
 * <p>Chain types in {@link #PARTIAL_OBSERVATION} are never covered: their ingester links transfers to
 * deployments (K2) but cannot observe every balance movement, so netting the indexed history would
 * call an unreconciled register reconciled. Stellar (Stage 1) only sees operations touching the
 * issuer account - holder-to-holder payments, DEX fills and claimable balances are invisible - and
 * leaves the set once the balance-authoritative Stage 2 check exists. Canton (K2) is
 * covered from a live indexer state. Solana is partial too: the mint-address
 * signature listing misses plain SPL {@code transfer} instructions (no mint account), so it leaves the
 * set only once token accounts are indexed (the mint-cursor checks below stay for that day).
 *
 * <p>{@code registerwerk.indexer.coverage.enforce=false} (test profile / demo seeding only) makes
 * every deployment covered; it must not be disabled in production.
 */
@Component
class IndexingCoverageService implements IndexingCoverage {

    /** Chain types whose ingester cannot observe every balance movement (reason shown to the operator). */
    static final Map<ChainConfig.ChainType, String> PARTIAL_OBSERVATION = new EnumMap<>(Map.of(
            ChainConfig.ChainType.SOLANA,
            "plain SPL transfers not observable (signatures are listed by mint address, but a legacy "
                    + "`transfer` instruction does not reference the mint; token-account indexing is pending)",
            ChainConfig.ChainType.STELLAR,
            "holder-to-holder transfers not observable (only operations touching the issuer account are "
                    + "indexed; balance-authoritative Stellar evidence is pending)"));

    private static final Map<ChainConfig.ChainType, IndexerState.IndexerType> INDEXER_TYPE =
            new EnumMap<>(Map.of(
                    ChainConfig.ChainType.EVM, IndexerState.IndexerType.GRAPH_NODE,
                    ChainConfig.ChainType.SOLANA, IndexerState.IndexerType.SOLANA_POLL,
                    ChainConfig.ChainType.STARKNET, IndexerState.IndexerType.STARKNET_POLL,
                    ChainConfig.ChainType.STELLAR, IndexerState.IndexerType.STELLAR_HORIZON,
                    ChainConfig.ChainType.CANTON, IndexerState.IndexerType.CANTON_STREAM));

    private final ChainConfigRepository chainConfigRepository;
    private final IndexerStateRepository indexerStateRepository;
    private final SolanaMintSyncCursorRepository solanaMintSyncCursorRepository;
    private final boolean enforce;
    private final Duration maxStaleness;

    IndexingCoverageService(ChainConfigRepository chainConfigRepository,
                            IndexerStateRepository indexerStateRepository,
                            SolanaMintSyncCursorRepository solanaMintSyncCursorRepository,
                            @Value("${registerwerk.indexer.coverage.enforce:true}") boolean enforce,
                            @Value("${registerwerk.indexer.coverage.max-staleness:PT2H}") Duration maxStaleness) {
        this.chainConfigRepository = chainConfigRepository;
        this.indexerStateRepository = indexerStateRepository;
        this.solanaMintSyncCursorRepository = solanaMintSyncCursorRepository;
        this.enforce = enforce;
        this.maxStaleness = maxStaleness;
    }

    @Override
    public Coverage evaluate(AssetDeployment deployment) {
        if (!enforce) {
            return Coverage.ok();
        }
        if (deployment.getContractAddress() == null || deployment.getContractAddress().isBlank()) {
            return Coverage.notIndexed("deployment has no contract address / mint / issuer to index");
        }
        if (deployment.getDeploymentStatus() == AssetDeployment.DeploymentStatus.FAILED) {
            return Coverage.notIndexed("deployment failed");
        }
        if (deployment.getChainConfigId() == null) {
            return Coverage.notIndexed("deployment is not linked to a chain configuration");
        }
        Optional<ChainConfig> found = chainConfigRepository.findById(deployment.getChainConfigId());
        if (found.isEmpty()) {
            return Coverage.notIndexed("chain configuration " + deployment.getChainConfigId() + " not found");
        }
        ChainConfig chain = found.get();
        ChainConfig.ChainType type = chain.getChainType();
        String where = chain.getIdentifier() + " (" + type + ")";
        String partial = PARTIAL_OBSERVATION.get(type);
        if (partial != null) {
            return Coverage.notIndexed("indexing of " + where + " is incomplete: " + partial
                    + "; holder balances cannot be reconciled from indexed history");
        }
        if (type == ChainConfig.ChainType.EVM
                && (chain.getGraphNodeUrl() == null || chain.getGraphNodeUrl().isBlank())) {
            return Coverage.notIndexed("chain " + where + " has no Graph Node configured, nothing indexes it");
        }
        IndexerState.IndexerType indexerType = INDEXER_TYPE.get(type);
        if (indexerType == null) {
            return Coverage.notIndexed("no indexer exists for chain type " + type);
        }
        Optional<IndexerState> state = indexerStateRepository
                .findByChainConfigIdAndIndexerType(chain.getId(), indexerType);
        if (state.isEmpty()) {
            return Coverage.notIndexed("no " + indexerType + " indexer state for " + where + " (never ran)");
        }
        IndexerState s = state.get();
        if (s.getStatus() != IndexerState.IndexerStatus.ACTIVE) {
            return Coverage.notIndexed(indexerType + " indexer for " + where + " is " + s.getStatus()
                    + (s.getLastError() == null ? "" : ": " + s.getLastError()));
        }
        Instant threshold = Instant.now().minus(maxStaleness);
        if (s.getLastSyncedAt() == null || s.getLastSyncedAt().isBefore(threshold)) {
            return Coverage.notIndexed(indexerType + " indexer for " + where + " is stale (last synced "
                    + s.getLastSyncedAt() + ", allowed " + maxStaleness + ")");
        }
        if (type == ChainConfig.ChainType.SOLANA) {
            Optional<SolanaMintSyncCursor> cursor = solanaMintSyncCursorRepository
                    .findByChainConfigIdAndMintAddress(chain.getId(), deployment.getContractAddress());
            if (cursor.isEmpty()) {
                return Coverage.notIndexed("mint " + deployment.getContractAddress()
                        + " is not tracked by the Solana indexer on " + where);
            }
            Instant cursorSyncedAt = cursor.get().getLastSyncedAt();
            if (cursorSyncedAt == null) {
                return Coverage.notIndexed("mint " + deployment.getContractAddress()
                        + " is registered but has not completed a sync pass yet on " + where);
            }
            if (cursorSyncedAt.isBefore(threshold)) {
                return Coverage.notIndexed("mint " + deployment.getContractAddress()
                        + " is stale (last synced " + cursorSyncedAt + ", allowed " + maxStaleness + ") on " + where);
            }
        }
        return Coverage.ok();
    }
}
