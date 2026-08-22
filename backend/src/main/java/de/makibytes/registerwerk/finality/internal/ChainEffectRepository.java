package de.makibytes.registerwerk.finality.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ChainEffectRepository extends JpaRepository<ChainEffect, UUID> {

    Optional<ChainEffect> findBySourceEventKeyAndEffectTypeAndEntityId(
            String sourceEventKey, String effectType, UUID entityId);

    /** {@code FinalityGateImpl}'s freeze check: true when {@code assetId} has at least one
     *  compensation that failed or was escalated as irreversible and no admin has acknowledged it
     *  yet — the condition under which every {@code GatedOperation} on this asset is blocked,
     *  regardless of the requested operation's own required level. */
    boolean existsByAssetIdAndStatusInAndAcknowledgedAtIsNull(UUID assetId, List<ChainEffect.Status> statuses);

    /** The operator "unresolved compensation" queue — every row that failed or was escalated,
     *  newest first, regardless of acknowledgement (so an operator can see what was already
     *  handled, not just what's still open). */
    List<ChainEffect> findByStatusInOrderByRecordedAtDesc(List<ChainEffect.Status> statuses);

    /** The retry job's query ({@code ChainEffectRetryJob}): {@code COMPENSATION_FAILED} rows below
     *  the retry attempt cap, oldest first — served by the same {@code idx_chain_effect_status
     *  (status, recorded_at)} index V5 added specifically anticipating this ("the retry job's
     *  query: everything still ACTIVE or COMPENSATION_FAILED, oldest first"). Rows at or above the
     *  cap are excluded from automatic retry but stay COMPENSATION_FAILED, still visible in the
     *  unresolved-compensation queue and still retryable on-demand via
     *  {@code FinalityJournalAdminService#retry}, which calls the dispatcher directly and is not
     *  subject to this cap. */
    List<ChainEffect> findByStatusAndAttemptCountLessThanOrderByRecordedAtAsc(
            ChainEffect.Status status, int maxAttempts);

    /** Atomic claim: proceeds if this row is {@code ACTIVE} or {@code COMPENSATION_FAILED} (a
     *  fresh or retried attempt), or if it has been stuck {@code COMPENSATING} since before
     *  {@code staleBefore} (the compensator that claimed it presumably crashed mid-run and never
     *  resolved it) — so two dispatchers racing on the same row never both run the compensator,
     *  while a genuinely abandoned claim is still eventually reclaimable. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChainEffect c SET c.status = de.makibytes.registerwerk.finality.internal.ChainEffect.Status.COMPENSATING, "
            + "c.claimedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND ("
            + "c.status IN (de.makibytes.registerwerk.finality.internal.ChainEffect.Status.ACTIVE, "
            + "de.makibytes.registerwerk.finality.internal.ChainEffect.Status.COMPENSATION_FAILED) "
            + "OR (c.status = de.makibytes.registerwerk.finality.internal.ChainEffect.Status.COMPENSATING "
            + "AND (c.claimedAt IS NULL OR c.claimedAt < :staleBefore)))")
    int claimForCompensation(@Param("id") UUID id, @Param("staleBefore") Instant staleBefore);

    /** Distinct effect types among rows still ACTIVE — the startup self-check verifies each has a
     *  registered compensator. */
    @Query("SELECT DISTINCT c.effectType FROM ChainEffect c "
            + "WHERE c.status = de.makibytes.registerwerk.finality.internal.ChainEffect.Status.ACTIVE")
    List<String> findDistinctActiveEffectTypes();

    /** Every effect recorded at or after a fork block, in any status, most-recently-recorded
     *  first — the generic retraction sweep ({@code BlockFinalityServiceImpl.recordRetraction})
     *  compensates each one in that order regardless of whether it is still ACTIVE or already
     *  SETTLED (a reorg deep enough to retract a FINALIZED block un-settles it too). The ordering
     *  matters: the saga pattern compensates in the reverse of the order the forward actions
     *  happened (LIFO) — e.g. an identity registration followed by its removal must be undone
     *  removal-first, or the final state depends on iteration order — and {@code recordedAt} is
     *  exactly when each effect's forward action was journalled. Relies on
     *  {@link de.makibytes.registerwerk.finality.api.ChainEffectCompensator} idempotency to make
     *  re-compensating an already-COMPENSATED row a safe no-op regardless of order beyond that.
     */
    @Query("SELECT c.id FROM ChainEffect c WHERE c.chainConfigId = :chainConfigId AND c.blockNumber >= :forkBlock "
            + "ORDER BY c.recordedAt DESC")
    List<UUID> findIdsAtOrAfter(@Param("chainConfigId") UUID chainConfigId, @Param("forkBlock") long forkBlock);

    /** Marks every still-ACTIVE effect at exactly this height SETTLED — called once the block
     *  reaches FINALIZED, mirroring {@code token_transfer}'s own trust model (only PROVISIONAL/SAFE
     *  rows are continuously re-probed; FINALIZED is trusted once reached). Without this, the
     *  "unresolved effects" set watched by {@link #findIdsAtOrAfter}-driven re-probing would grow
     *  without bound. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChainEffect c SET c.status = de.makibytes.registerwerk.finality.internal.ChainEffect.Status.SETTLED "
            + "WHERE c.chainConfigId = :chainConfigId AND c.blockNumber = :blockNumber "
            + "AND c.status = de.makibytes.registerwerk.finality.internal.ChainEffect.Status.ACTIVE")
    int settleAtBlock(@Param("chainConfigId") UUID chainConfigId, @Param("blockNumber") long blockNumber);

    /** Distinct block numbers among effects not yet SETTLED (still ACTIVE, or stuck in a
     *  COMPENSATING/COMPENSATION_FAILED state) for a chain — merged into {@code ReorgGuard}'s
     *  unsettled-block walk so a block whose <em>only</em> activity was a module effect with no
     *  correlated {@code token_transfer} row (e.g. an admin action like a pause) is still
     *  re-probed for a reorg, not silently unwatched. */
    @Query("SELECT DISTINCT c.blockNumber FROM ChainEffect c WHERE c.chainConfigId = :chainConfigId "
            + "AND c.status <> de.makibytes.registerwerk.finality.internal.ChainEffect.Status.SETTLED "
            + "AND c.status <> de.makibytes.registerwerk.finality.internal.ChainEffect.Status.COMPENSATED "
            + "AND c.status <> de.makibytes.registerwerk.finality.internal.ChainEffect.Status.IRREVERSIBLE_ESCALATED")
    List<Long> findDistinctUnresolvedBlockNumbers(@Param("chainConfigId") UUID chainConfigId);
}
