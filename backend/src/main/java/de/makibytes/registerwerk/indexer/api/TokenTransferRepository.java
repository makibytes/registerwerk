package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.finality.api.FinalityLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link TokenTransfer} entities.
 */
public interface TokenTransferRepository extends JpaRepository<TokenTransfer, UUID> {

    /** Returns all transfers for a given contract address, newest first. */
    Page<TokenTransfer> findByContractAddressOrderByOccurredAtDesc(String contractAddress, Pageable pageable);

    /** Returns all transfers linked to a specific asset, newest first. */
    Page<TokenTransfer> findByAssetIdOrderByOccurredAtDesc(UUID assetId, Pageable pageable);

    /** Returns all transfers linked to a specific deployment, newest first. */
    Page<TokenTransfer> findByDeploymentIdOrderByOccurredAtDesc(UUID deploymentId, Pageable pageable);

    /** Same as {@link #findByDeploymentIdOrderByOccurredAtDesc} but restricted to a single
     *  {@link FinalityLevel} — used by balance-authoritative reads (holder sync) where a
     *  reorged-out (ORPHANED) or not-yet-confirmed (PROVISIONAL/SAFE) transfer must never be
     *  allowed to move the register's balance. Pass {@code FINALIZED}. */
    Page<TokenTransfer> findByDeploymentIdAndFinalityStatusOrderByOccurredAtDesc(
            UUID deploymentId, FinalityLevel finalityStatus, Pageable pageable);

    /**
     * Returns transfers on a given chain that occurred after (strictly greater than) the
     * given block number. Used by the indexer to resume from a checkpoint.
     */
    List<TokenTransfer> findByChainConfigIdAndBlockNumberGreaterThan(UUID chainConfigId, Long fromBlock);

    /**
     * Deduplication check: returns true if a transfer with the same chain, transaction hash,
     * and log index already exists in the database.
     *
     * @param logIndex null is valid for Solana transfers (no EVM log index)
     */
    boolean existsByChainConfigIdAndTxHashAndLogIndex(UUID chainConfigId, String txHash, Integer logIndex);

    /**
     * Looks up one exact EVM log occurrence, including its block incarnation. {@code occurredAt}
     * is the immutable block timestamp and also the table's RANGE partition key; including it
     * makes this lookup match the database uniqueness constraint. An ORPHANED result is not a
     * duplicate: Graph Node ingest reactivates it when the same block becomes canonical again.
     */
    Optional<TokenTransfer> findByChainConfigIdAndTxHashAndLogIndexAndBlockHashAndOccurredAt(
            UUID chainConfigId, String txHash, Integer logIndex, String blockHash, Instant occurredAt);

    /**
     * Returns the most recent transfer for a given chain, used to determine the high-water mark
     * for the next sync window.
     */
    Optional<TokenTransfer> findTopByChainConfigIdOrderByBlockNumberDesc(UUID chainConfigId);

    // ── Reorg / finality (ReorgGuard) ───────────────────────────────────────

    /** Distinct block numbers among currently PROVISIONAL-or-SAFE rows for a chain — the "unsettled
     *  window" ReorgGuard re-verifies each tick. Ascending so the first mismatch found is the
     *  earliest (true) fork point. SAFE rows stay in this walk (not just PROVISIONAL ones) so a
     *  block that reaches SAFE keeps being re-probed on later ticks until it either reaches
     *  FINALIZED or is orphaned — otherwise a SAFE row would never advance further once it left
     *  PROVISIONAL, since nothing else re-visits it. Already-FINALIZED rows are excluded: the
     *  model's promise is trusted once reached, and any reorg deep enough to reach one anyway is
     *  still caught by {@link #markOrphanedFromBlock}'s bulk "everything at/after the fork point"
     *  sweep, triggered by some other still-unsettled block on this same walk. */
    @Query("SELECT DISTINCT t.blockNumber FROM TokenTransfer t "
            + "WHERE t.chainConfigId = :chainConfigId AND t.finalityStatus IN ('PROVISIONAL', 'SAFE') "
            + "AND t.blockNumber IS NOT NULL ORDER BY t.blockNumber ASC")
    List<Long> findDistinctUnsettledBlocks(@Param("chainConfigId") UUID chainConfigId);

    /** The live block hash(es) previously recorded for a given chain+height — normally a single
     * distinct value; historical ORPHANED incarnations are excluded so they cannot make a later
     * A→B or A→B→A self-probe look like the fresh canonical hash was already present. */
    @Query("SELECT DISTINCT t.blockHash FROM TokenTransfer t "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockNumber = :blockNumber "
            + "AND t.blockHash IS NOT NULL AND t.finalityStatus <> 'ORPHANED'")
    List<String> findDistinctBlockHashesAt(@Param("chainConfigId") UUID chainConfigId, @Param("blockNumber") Long blockNumber);

    /** Flips every row at exactly this height currently at {@code fromStatus} to {@code toStatus}.
     *  Bulk/set-based — bypasses the persistence context, so callers must not rely on in-memory
     *  entities reflecting this afterwards within the same transaction.
     *
     *  <p>Promotion is deliberately one specific {@code fromStatus} at a time, not "anything below
     *  toStatus" — the caller (ReorgGuard) calls this once per plausible source status (a
     *  FINALIZED verdict promotes both PROVISIONAL and SAFE rows via two calls; a SAFE verdict
     *  promotes only PROVISIONAL rows via one), so a single well-known source status per call
     *  keeps this monotonic without needing a CASE expression: a row already at FINALIZED is never
     *  touched by a later call targeting a weaker source status, so a transient probe regression
     *  can never demote it back down. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TokenTransfer t SET t.finalityStatus = :toStatus "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockNumber = :blockNumber "
            + "AND t.finalityStatus = :fromStatus")
    int markLevelAtBlock(@Param("chainConfigId") UUID chainConfigId, @Param("blockNumber") Long blockNumber,
            @Param("fromStatus") FinalityLevel fromStatus, @Param("toStatus") FinalityLevel toStatus);

    /** Marks every non-ORPHANED row at or after the fork block ORPHANED — never deletes. Applies
     *  to PROVISIONAL, SAFE, and (in the rare case of a reorg deeper than the confirmation policy)
     *  already-FINALIZED rows, matching the "everything at and after the fork point" algorithm. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TokenTransfer t SET t.finalityStatus = 'ORPHANED' "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockNumber >= :forkBlock "
            + "AND t.finalityStatus <> 'ORPHANED'")
    int markOrphanedFromBlock(@Param("chainConfigId") UUID chainConfigId, @Param("forkBlock") Long forkBlock);

    /** Exact-incarnation variant for Chaincache's typed reorg lineage. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TokenTransfer t SET t.finalityStatus = 'ORPHANED' "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockHash IN :blockHashes "
            + "AND t.finalityStatus <> 'ORPHANED'")
    int markOrphanedByBlockHashes(
            @Param("chainConfigId") UUID chainConfigId,
            @Param("blockHashes") List<String> blockHashes);

    /** Policy-drift guard for typed reorgs: Chaincache may label an episode routine while this
     * Registerwerk instance has already finalized the exact occurrence under a stronger/local
     * policy. Such rows must be quarantined, never automatically orphaned. */
    @Query("SELECT COUNT(t) > 0 FROM TokenTransfer t "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockHash IN :blockHashes "
            + "AND t.finalityStatus = 'FINALIZED'")
    boolean existsFinalizedByBlockHashes(
            @Param("chainConfigId") UUID chainConfigId,
            @Param("blockHashes") List<String> blockHashes);

    /** Assets touched by still-live rows from exactly the block incarnations in a typed reorg
     * episode. The status predicate is the replay/idempotency boundary: once the rows are
     * ORPHANED, duplicate delivery must not emit a second holder-recompute effect. */
    @Query("SELECT DISTINCT t.assetId FROM TokenTransfer t "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockHash IN :blockHashes "
            + "AND t.finalityStatus <> 'ORPHANED' AND t.assetId IS NOT NULL")
    List<UUID> findDistinctAssetIdsByBlockHashes(
            @Param("chainConfigId") UUID chainConfigId,
            @Param("blockHashes") List<String> blockHashes);

    /** True if any already-FINALIZED row is at or after the fork block — signals a reorg deeper
     *  than the configured confirmation depth (a CRITICAL condition, logged separately by the
     *  caller). */
    @Query("SELECT COUNT(t) > 0 FROM TokenTransfer t "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockNumber >= :forkBlock "
            + "AND t.finalityStatus = 'FINALIZED'")
    boolean existsFinalizedAtOrAfter(@Param("chainConfigId") UUID chainConfigId, @Param("forkBlock") Long forkBlock);

    /** True if any already-SAFE-or-stronger row is at or after the fork block — a reorg reaching
     *  this deep is still within the confirmation policy's promise (SAFE is not final) but is a
     *  materially more serious event than one confined to the PROVISIONAL window, and the caller
     *  logs/alerts at a different severity than a shallow, purely-PROVISIONAL reorg. */
    @Query("SELECT COUNT(t) > 0 FROM TokenTransfer t "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockNumber >= :forkBlock "
            + "AND t.finalityStatus IN ('SAFE', 'FINALIZED')")
    boolean existsSafeAtOrAfter(@Param("chainConfigId") UUID chainConfigId, @Param("forkBlock") Long forkBlock);

    /** Distinct asset ids among rows at or after the fork block — used to find which assets a
     *  reorg affected, so their holder balances can be recompensated. Rows a reorg's bulk
     *  {@link #markOrphanedFromBlock} update has already flipped to ORPHANED are still matched
     *  here (the filter is purely by block range, not current status), so this stays correct
     *  whether it runs before or after that update. */
    @Query("SELECT DISTINCT t.assetId FROM TokenTransfer t "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockNumber >= :forkBlock AND t.assetId IS NOT NULL")
    List<UUID> findDistinctAssetIdsAtOrAfter(@Param("chainConfigId") UUID chainConfigId, @Param("forkBlock") Long forkBlock);

    /** Backfills a block hash onto rows at a height that were written without one (e.g. a
     *  transient _meta lookup failure at insert time) — lets a later successful probe establish
     *  a comparison baseline it didn't have on the first pass. Never overwrites an existing hash. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TokenTransfer t SET t.blockHash = :hash "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockNumber = :blockNumber AND t.blockHash IS NULL")
    int backfillBlockHashAtBlock(@Param("chainConfigId") UUID chainConfigId, @Param("blockNumber") Long blockNumber, @Param("hash") String hash);
}
