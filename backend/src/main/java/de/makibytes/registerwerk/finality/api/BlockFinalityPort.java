package de.makibytes.registerwerk.finality.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of the {@code block_finality} ledger. See {@link BlockFinalityFeed}'s javadoc for
 *  what is and isn't tracked here. */
public interface BlockFinalityPort {

    /** The current canonical incarnation for this height, if one is known. Empty
     *  does not mean "not final" — it usually means the block never entered the unsettled window
     *  at all, was never recorded, or all known incarnations were retracted. */
    Optional<BlockFinalityRecord> find(UUID chainConfigId, long blockNumber);

    /** Every incarnation ever recorded at this height, oldest first. Unlike {@link #find}, this
     *  is an audit/history query and includes orphaned incarnations. */
    List<BlockFinalityRecord> findIncarnations(UUID chainConfigId, long blockNumber);

    /**
     * Distinct block numbers on this chain that carry at least one not-yet-resolved
     * {@code chain_effect} (recorded by any module via {@code ChainEffectRecorder}, still ACTIVE
     * or stuck mid-compensation). Merged by {@code ReorgGuard} into its own unsettled-block walk
     * so a block whose only activity was a module effect with no correlated {@code token_transfer}
     * row (e.g. a pause/whitelist admin action) is still re-probed for a reorg, rather than being
     * silently outside the window {@code token_transfer}-only tracking would otherwise define.
     */
    List<Long> findBlocksWithUnresolvedEffects(UUID chainConfigId);

    /** {@code orphanedAt} is the most recent orphaning time; consult retraction audit events for
     *  complete episode history. */
    record BlockFinalityRecord(
            UUID chainConfigId, long blockNumber, String blockHash, FinalityLevel level,
            boolean canonical, Instant observedAt, Instant updatedAt, Instant orphanedAt) {
    }
}
