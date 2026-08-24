package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.api.BlockIdentity;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.blockchain.api.ReorgProjectionPort;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TypedReorgCompensationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Exact, replay-safe indexer side of a typed Chaincache reorg episode. */
@Service
class TypedReorgApplicationService implements ReorgProjectionPort {

    private final TokenTransferRepository transferRepository;
    private final IndexerStateRepository indexerStateRepository;
    private final ChainEffectRecorder effectRecorder;
    private final JdbcTemplate jdbcTemplate;

    TypedReorgApplicationService(TokenTransferRepository transferRepository,
            IndexerStateRepository indexerStateRepository,
            ChainEffectRecorder effectRecorder, JdbcTemplate jdbcTemplate) {
        this.transferRepository = transferRepository;
        this.indexerStateRepository = indexerStateRepository;
        this.effectRecorder = effectRecorder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public AppliedReorg apply(UUID chainConfigId, ReorgObservation observation) {
        // Serialize episodes for one chain even if two application replicas receive work during
        // a lease handoff. The transaction-scoped lock is released on commit/rollback.
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (rs, rowNum) -> rs.getObject(1),
                "typed-indexer-reorg:" + chainConfigId);

        if (observation.severity() != ReorgObservation.ReorgSeverity.ROUTINE
                || observation.orphanedLineage().isEmpty()) {
            // UNRESOLVED_ANCESTRY intentionally quarantines without guessing which transfer rows
            // to mutate. Exact identity is a prerequisite for an auditable compensation.
            return new AppliedReorg(0, List.of(), false);
        }

        List<String> orphanedHashes = observation.orphanedLineage().stream()
                .map(ReorgObservation.BlockReference::blockHash)
                .map(BlockIdentity::normalize)
                .distinct()
                .toList();
        if (transferRepository.existsFinalizedByBlockHashes(chainConfigId, orphanedHashes)) {
            return new AppliedReorg(0, List.of(), true);
        }

        // Cursor repair is part of the same database transaction as orphaning and holder
        // compensation. Even when this Registerwerk has no transfer in the orphaned lineage, it
        // must revisit the replacement suffix on the next Graph Node tick. The repository update
        // is atomic and min-wise, so replay or an older concurrent episode cannot move it forward.
        long forkBlock = observation.forkBlockNumber();
        Long rewindBlock = forkBlock == 0 ? null : forkBlock - 1;
        indexerStateRepository.rewindBlockCursor(
                chainConfigId, IndexerState.IndexerType.GRAPH_NODE, rewindBlock);

        List<UUID> affectedAssets = transferRepository.findDistinctAssetIdsByBlockHashes(
                chainConfigId, orphanedHashes);
        int orphaned = transferRepository.markOrphanedByBlockHashes(chainConfigId, orphanedHashes);

        ReorgObservation.BlockReference first = observation.orphanedLineage().getFirst();
        UUID correlationId = stableCorrelationId(observation.reorgId());
        for (UUID assetId : affectedAssets) {
            CompensationOutcome outcome = effectRecorder.recordAndCompensate(new ChainEffectDescriptor(
                    chainConfigId,
                    first.blockNumber(),
                    null,
                    null,
                    null,
                    "indexer",
                    HolderRecomputeCompensator.EFFECT_TYPE,
                    "Asset",
                    assetId,
                    assetId,
                    CompensationCategory.RECOMPUTE,
                    null,
                    java.util.Map.of(
                            "reorgId", observation.reorgId(),
                            "orphanedBlockHashes", orphanedHashes),
                    null,
                    correlationId));
            if (outcome instanceof CompensationOutcome.Failed failed) {
                throw new TypedReorgCompensationException(
                        "Holder recompute failed for asset=" + assetId + ": " + failed.reason());
            }
            if (outcome instanceof CompensationOutcome.Irreversible irreversible) {
                throw new TypedReorgCompensationException(
                        "Holder recompute was irreversible for asset=" + assetId + ": "
                                + irreversible.remediationHint());
            }
        }
        return new AppliedReorg(orphaned, affectedAssets, false);
    }

    private static UUID stableCorrelationId(String reorgId) {
        try {
            return UUID.fromString(reorgId);
        } catch (IllegalArgumentException notAUuid) {
            return UUID.nameUUIDFromBytes(reorgId.getBytes(StandardCharsets.UTF_8));
        }
    }
}
