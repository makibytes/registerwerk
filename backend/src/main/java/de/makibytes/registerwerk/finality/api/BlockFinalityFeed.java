package de.makibytes.registerwerk.finality.api;

import java.util.UUID;

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

    /**
     * Records a promotion of block {@code blockNumber} on {@code chainConfigId} to {@code level}
     * (SAFE or FINALIZED only — see {@link FinalityLevel}). Idempotent: recording the same level
     * twice, or a level weaker than what is already stored, is a no-op; a stored ORPHANED is
     * terminal and is never overwritten by this method (only {@link #recordRetraction} writes
     * ORPHANED). Publishes {@code BlockFinalityChangedEvent} only when the stored level actually
     * changes, not on every re-observation.
     *
     * @throws IllegalArgumentException if {@code level} is {@link FinalityLevel#ORPHANED}
     */
    void recordObservation(UUID chainConfigId, long blockNumber, String blockHash, FinalityLevel level);

    /**
     * Records that a reorg was detected at {@code forkBlockNumber} on {@code chainConfigId} —
     * every previously-recorded block at or after that height is marked ORPHANED (never deleted)
     * and {@code BlockRetractedEvent} is published exactly once for the whole retraction.
     *
     * @param replacementBlockHash the freshly-observed canonical hash at {@code forkBlockNumber}
     *                             that no longer matches what was previously recorded there, or
     *                             null if not available (e.g. a chain-specific probe with no hash
     *                             concept, such as Starknet's status-based detection)
     * @param orphanedTransferCount how many {@code token_transfer} rows this reorg orphaned —
     *                              carried through only for the event payload/audit trail, not
     *                              used to decide what to orphan in this ledger (that is purely
     *                              by block number, independent of transfer volume)
     */
    void recordRetraction(UUID chainConfigId, long forkBlockNumber, String replacementBlockHash,
            int orphanedTransferCount);
}
