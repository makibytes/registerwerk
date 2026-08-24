package de.makibytes.registerwerk.finality.api;

import java.util.UUID;
import java.util.List;

/**
 * Write side of the {@code block_finality} ledger — called by the indexer's reorg-detection
 * logic ({@code ReorgGuard}) so that "what level did block N on chain C reach, and was it ever
 * retracted" lives in one place this module owns, instead of only being derivable by scanning
 * {@code token_transfer} rows (which is per-transfer, not per-block, and says nothing about a
 * block with no transfers on it).
 *
 * <p>Deliberately narrow for now: only blocks that entered the "unsettled" (PROVISIONAL/SAFE)
 * window are recorded here — a block written straight to FINALIZED at insert time (already deep
 * enough) never had a "could this still be reorged" question to answer, and its finality is
 * already correctly reflected on the {@code token_transfer} rows it produced. Widening this to
 * mirror every observed block is a later concern, not needed to close "nothing reacts to
 * ORPHANED".
 */
public interface BlockFinalityFeed {

    /** True once this exact durable episode has committed locally. Durable multi-stream delivery
     * uses this check before indexer mutation so a duplicate cannot re-enter compensation after
     * the first copy activated chain quarantine. */
    boolean isReorgRecorded(UUID chainConfigId, ReorgObservation observation);

    /** Locks the chain and reports whether an upstream routine episode intersects a block/effect
     * occurrence already finalized under Registerwerk's local policy. Called inside the shared
     * reorg coordinator transaction before any indexer mutation. */
    boolean hasLocalFinalityConflict(UUID chainConfigId, ReorgObservation observation);

    /**
     * Fail-closed bridge for legacy/self-probe retractions that know a fork height and observed
     * hashes but cannot supply a fully parent-linked typed lineage. If the range intersects local
     * FINALIZED blocks or SETTLED effects, persists an unresolved-ancestry episode and chain
     * quarantine atomically and returns {@code true}; callers must leave projections untouched.
     */
    boolean quarantineUnverifiableFinalizedRetraction(
            UUID chainConfigId, long forkBlockNumber, List<String> storedBlockHashes,
            String replacementBlockHash);

    /**
     * Records a promotion of block {@code blockNumber} on {@code chainConfigId} to {@code level}
     * (SAFE or FINALIZED only — see {@link FinalityLevel}). Idempotent: recording the same level
     * twice, or a level weaker than what the same canonical incarnation already stored, is a
     * no-op. An orphaned incarnation may become canonical again after a later reorg (A→B→A); in
     * that case its level is reset to this fresh observation while its orphaned timestamp remains
     * audit evidence. Publishes {@code BlockFinalityChangedEvent} only when canonical state or the
     * stored level actually changes, not on every re-observation.
     *
     * <p>If another hash is still canonical at the height, callers must invoke
     * {@link #recordRetraction} first. This method never performs a silent canonical swap because
     * doing so would bypass compensation and the auditable retraction event.
     *
     * @throws IllegalArgumentException if {@code level} is {@link FinalityLevel#ORPHANED}
     */
    void recordObservation(UUID chainConfigId, long blockNumber, String blockHash, FinalityLevel level);

    /**
     * Records that a reorg was detected at {@code forkBlockNumber} on {@code chainConfigId} —
     * every canonical block at or after that height is marked ORPHANED/non-canonical (never
     * deleted) and {@code BlockRetractedEvent} is published exactly once for the whole retraction.
     *
     * @param replacementBlockHash the freshly-observed canonical hash at {@code forkBlockNumber}
     *                             that no longer matches what was previously recorded there, or
     *                             null if not available (e.g. a chain-specific probe with no hash
     *                             concept, such as Starknet's status-based detection)
     * @param orphanedTransferCount how many {@code token_transfer} rows this reorg orphaned —
     *                              carried through only for the event payload/audit trail, not
     *                              used to decide what to orphan in this ledger (that is purely
     *                              by block number, independent of transfer volume)
     * @throws IllegalStateException if the height-only range intersects a SETTLED effect. A deep
     *                               reorg requires {@link #recordReorg} so the exact occurrence
     *                               and finality-violation quarantine can be persisted.
     */
    void recordRetraction(UUID chainConfigId, long forkBlockNumber, String replacementBlockHash,
            int orphanedTransferCount);

    /**
     * Applies a versioned Chaincache reorg occurrence exactly once. Unlike the legacy
     * height-range method, this changes only the explicitly orphaned block identities, so a
     * commit-before-ack replay can never orphan a replacement lineage already made canonical.
     */
    default void recordReorg(UUID chainConfigId, ReorgObservation observation, int orphanedTransferCount) {
        recordReorg(chainConfigId, observation, orphanedTransferCount, null);
    }

    default void recordReorg(UUID chainConfigId, ReorgObservation observation, int orphanedTransferCount,
            boolean indexerSafetyConflict) {
        recordReorg(chainConfigId, observation, orphanedTransferCount,
                indexerSafetyConflict ? QuarantineTrigger.LOCAL_FINALITY_CONFLICT : null);
    }

    void recordReorg(UUID chainConfigId, ReorgObservation observation, int orphanedTransferCount,
            QuarantineTrigger safetyConflict);
}
