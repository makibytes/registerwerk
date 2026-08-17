package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * Returns the most recent transfer for a given chain, used to determine the high-water mark
     * for the next sync window.
     */
    Optional<TokenTransfer> findTopByChainConfigIdOrderByBlockNumberDesc(UUID chainConfigId);

    // ── Reorg / finality (ReorgGuard) ───────────────────────────────────────

    /** Distinct block numbers among currently PROVISIONAL rows for a chain — the "provisional
     *  window" ReorgGuard re-verifies each tick. Ascending so the first mismatch found is the
     *  earliest (true) fork point. */
    @Query("SELECT DISTINCT t.blockNumber FROM TokenTransfer t "
            + "WHERE t.chainConfigId = :chainConfigId AND t.finalityStatus = 'PROVISIONAL' "
            + "AND t.blockNumber IS NOT NULL ORDER BY t.blockNumber ASC")
    List<Long> findDistinctProvisionalBlocks(@Param("chainConfigId") UUID chainConfigId);

    /** The block hash(es) previously recorded for a given chain+height — normally a single
     *  distinct value; more than one indicates the rows themselves already disagree. */
    @Query("SELECT DISTINCT t.blockHash FROM TokenTransfer t "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockNumber = :blockNumber "
            + "AND t.blockHash IS NOT NULL")
    List<String> findDistinctBlockHashesAt(@Param("chainConfigId") UUID chainConfigId, @Param("blockNumber") Long blockNumber);

    /** Flips every PROVISIONAL row at exactly this height to FINAL. Bulk/set-based — bypasses
     *  the persistence context, so callers must not rely on in-memory entities reflecting this
     *  afterwards within the same transaction. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TokenTransfer t SET t.finalityStatus = 'FINAL' "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockNumber = :blockNumber "
            + "AND t.finalityStatus = 'PROVISIONAL'")
    int markFinalAtBlock(@Param("chainConfigId") UUID chainConfigId, @Param("blockNumber") Long blockNumber);

    /** Marks every non-ORPHANED row at or after the fork block ORPHANED — never deletes. Applies
     *  to both PROVISIONAL and (in the rare case of a reorg deeper than the confirmation policy)
     *  already-FINAL rows, matching the "everything at and after the fork point" algorithm. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TokenTransfer t SET t.finalityStatus = 'ORPHANED' "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockNumber >= :forkBlock "
            + "AND t.finalityStatus <> 'ORPHANED'")
    int markOrphanedFromBlock(@Param("chainConfigId") UUID chainConfigId, @Param("forkBlock") Long forkBlock);

    /** True if any already-FINAL row is at or after the fork block — signals a reorg deeper than
     *  the configured confirmation depth (a CRITICAL condition, logged separately by the caller). */
    @Query("SELECT COUNT(t) > 0 FROM TokenTransfer t "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockNumber >= :forkBlock "
            + "AND t.finalityStatus = 'FINAL'")
    boolean existsFinalAtOrAfter(@Param("chainConfigId") UUID chainConfigId, @Param("forkBlock") Long forkBlock);

    /** Backfills a block hash onto rows at a height that were written without one (e.g. a
     *  transient _meta lookup failure at insert time) — lets a later successful probe establish
     *  a comparison baseline it didn't have on the first pass. Never overwrites an existing hash. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TokenTransfer t SET t.blockHash = :hash "
            + "WHERE t.chainConfigId = :chainConfigId AND t.blockNumber = :blockNumber AND t.blockHash IS NULL")
    int backfillBlockHashAtBlock(@Param("chainConfigId") UUID chainConfigId, @Param("blockNumber") Long blockNumber, @Param("hash") String hash);
}
