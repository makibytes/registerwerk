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

    boolean existsByChainConfigIdAndStatusInAndAcknowledgedAtIsNull(
            UUID chainConfigId, List<ChainEffect.Status> statuses);

    /** The operator "unresolved compensation" queue — every row that failed or was escalated,
     *  newest first, regardless of acknowledgement (so an operator can see what was already
     *  handled, not just what's still open). */
    List<ChainEffect> findByStatusInOrderByRecordedAtDesc(List<ChainEffect.Status> statuses);

    /** The retry job's query ({@code ChainEffectRetryJob}): ids of {@code COMPENSATION_FAILED} rows below
     *  the retry attempt cap, oldest journal occurrence first. Rows at or above the
     *  cap are excluded from automatic retry but stay COMPENSATION_FAILED, still visible in the
     *  unresolved-compensation queue and still retryable on-demand via
     *  {@code FinalityJournalAdminService#retry}, which calls the dispatcher directly and is not
     *  subject to this cap. */
    @Query("SELECT c.id FROM ChainEffect c WHERE c.status = :status AND c.attemptCount < :maxAttempts "
            + "ORDER BY c.journalSequence ASC")
    List<UUID> findRetryableIds(
            @Param("status") ChainEffect.Status status, @Param("maxAttempts") int maxAttempts);

    /** Atomic claim: proceeds if this row is {@code ACTIVE} or {@code COMPENSATION_FAILED} (a
     *  fresh or retried attempt), or if it has been stuck {@code COMPENSATING} for five minutes
     *  (the compensator that claimed it presumably crashed mid-run and never resolved it) — so
     *  two dispatchers racing on the same row never both run the compensator, while a genuinely
     *  abandoned claim is still eventually reclaimable. Claim time and cutoff both use the
     *  database clock, preventing cross-pod clock skew from stealing a live claim. {@code SETTLED} is
     *  intentionally excluded: typed routine reorgs are forbidden from orphaning finalized
     *  effects, and legacy height-only retractions fail before mutation when they intersect one. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE chain_effect
            SET status = 'COMPENSATING', claimed_at = CURRENT_TIMESTAMP
            WHERE id = :id AND (
                status IN ('ACTIVE', 'COMPENSATION_FAILED')
                OR (status = 'COMPENSATING' AND (
                    claimed_at IS NULL
                    OR claimed_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'
                ))
            )
            """, nativeQuery = true)
    int claimForCompensation(@Param("id") UUID id);

    /** Distinct effect types among rows still ACTIVE — the startup self-check verifies each has a
     *  registered compensator. */
    @Query("SELECT DISTINCT c.effectType FROM ChainEffect c "
            + "WHERE c.status = de.makibytes.registerwerk.finality.internal.ChainEffect.Status.ACTIVE")
    List<String> findDistinctActiveEffectTypes();

    /** Every effect recorded at or after a fork block, most-recently-journalled first. The legacy
     *  generic retraction sweep calls this only after proving the range contains no SETTLED
     *  effect; a finalized intersection requires a typed finality-violation episode and quarantine. The ordering
     *  matters: the saga pattern compensates in the reverse of the order the forward actions
     *  happened (LIFO) — e.g. an identity registration followed by its removal must be undone
     *  removal-first, or the final state depends on iteration order. {@code journalSequence} is
     *  database-assigned because PostgreSQL gives every insert in one transaction the same
     *  {@code CURRENT_TIMESTAMP}, making {@code recordedAt} insufficient for ordering. Relies on
     *  {@link de.makibytes.registerwerk.finality.api.ChainEffectCompensator} idempotency to make
     *  re-compensating an already-COMPENSATED row a safe no-op regardless of order beyond that.
     */
    @Query("SELECT c.id FROM ChainEffect c WHERE c.chainConfigId = :chainConfigId AND c.blockNumber >= :forkBlock "
            + "ORDER BY c.journalSequence DESC")
    List<UUID> findIdsAtOrAfter(@Param("chainConfigId") UUID chainConfigId, @Param("forkBlock") long forkBlock);

    /** Exact-incarnation equivalent used by Chaincache's typed reorg envelope. */
    @Query("SELECT c.id FROM ChainEffect c WHERE c.chainConfigId = :chainConfigId "
            + "AND c.blockHash IN :blockHashes ORDER BY c.journalSequence DESC")
    List<UUID> findIdsByBlockHashes(
            @Param("chainConfigId") UUID chainConfigId,
            @Param("blockHashes") List<String> blockHashes);

    boolean existsByChainConfigIdAndStatusAndBlockHashIn(
            UUID chainConfigId, ChainEffect.Status status, List<String> blockHashes);

    /** A forward handler applied the same immutable source event again after its former canonical
     * tenure had already been compensated (A->B->A). Re-arm that logical effect for the new
     * tenure and allocate a fresh ordering position. The status predicate is the concurrency
     * fence: duplicate forward deliveries racing here can reactivate at most once. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE chain_effect
            SET status = 'ACTIVE',
                attempt_count = 0,
                resolution_detail = NULL,
                claimed_at = NULL,
                settled_at = NULL,
                compensated_at = NULL,
                acknowledged_by = NULL,
                acknowledged_at = NULL,
                acknowledge_reason = NULL,
                recorded_at = CURRENT_TIMESTAMP,
                journal_sequence = nextval('chain_effect_journal_sequence_seq')
            WHERE id = :id AND status = 'COMPENSATED'
            """, nativeQuery = true)
    int reactivateCompensated(@Param("id") UUID id);

    /** Marks every still-ACTIVE effect from this exact canonical block incarnation SETTLED — called once the block
     *  reaches FINALIZED, mirroring {@code token_transfer}'s own trust model (only PROVISIONAL/SAFE
     *  rows are continuously re-probed; FINALIZED is trusted once reached). Without this, the
     *  "unresolved effects" set watched by {@link #findIdsAtOrAfter}-driven re-probing would grow
     *  without bound. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChainEffect c SET c.status = de.makibytes.registerwerk.finality.internal.ChainEffect.Status.SETTLED "
            + "WHERE c.chainConfigId = :chainConfigId AND c.blockNumber = :blockNumber "
            + "AND ((:blockHash IS NULL AND c.blockHash IS NULL) OR c.blockHash = :blockHash) "
            + "AND c.status = de.makibytes.registerwerk.finality.internal.ChainEffect.Status.ACTIVE")
    int settleAtBlock(
            @Param("chainConfigId") UUID chainConfigId,
            @Param("blockNumber") long blockNumber,
            @Param("blockHash") String blockHash);

    boolean existsByChainConfigIdAndBlockNumberGreaterThanEqualAndStatus(
            UUID chainConfigId, long forkBlock, ChainEffect.Status status);

    /** Atomic break-glass acknowledgement. A concurrent retry may change the status while an
     * operator is reviewing the row; updating only these columns with a status predicate avoids
     * a stale JPA entity overwriting COMPENSATED back to COMPENSATION_FAILED. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChainEffect c SET c.acknowledgedBy = :actorId, c.acknowledgedAt = :acknowledgedAt, "
            + "c.acknowledgeReason = :reason WHERE c.id = :id AND c.acknowledgedAt IS NULL AND "
            + "c.status IN (de.makibytes.registerwerk.finality.internal.ChainEffect.Status.COMPENSATION_FAILED, "
            + "de.makibytes.registerwerk.finality.internal.ChainEffect.Status.IRREVERSIBLE_ESCALATED)")
    int acknowledgeIfUnresolved(
            @Param("id") UUID id,
            @Param("reason") String reason,
            @Param("actorId") UUID actorId,
            @Param("acknowledgedAt") Instant acknowledgedAt);

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
