package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.indexer.api.IndexerState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link IndexerState} entities.
 */
public interface IndexerStateRepository extends JpaRepository<IndexerState, UUID> {

    /**
     * Finds the indexer state record for a specific chain + indexer type combination.
     * Returns empty if no sync has been recorded yet.
     */
    Optional<IndexerState> findByChainConfigIdAndIndexerType(
            UUID chainConfigId, IndexerState.IndexerType indexerType);

    /**
     * Rewinds a block cursor without ever advancing it. The CASE expressions deliberately retain
     * null ("never synced") and make repeated/out-of-order reorg delivery min-wise. Clamping the
     * finalized cursor in the same statement preserves {@code lastFinalBlock <= lastSyncedBlock}.
     * A null target represents a fork at genesis and returns both cursors to their initial state.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE IndexerState s SET "
            + "s.lastSyncedBlock = CASE "
            + "WHEN :rewindBlock IS NULL THEN NULL "
            + "WHEN s.lastSyncedBlock IS NULL OR s.lastSyncedBlock <= :rewindBlock THEN s.lastSyncedBlock "
            + "ELSE :rewindBlock END, "
            + "s.lastFinalBlock = CASE "
            + "WHEN :rewindBlock IS NULL OR s.lastSyncedBlock IS NULL OR s.lastFinalBlock IS NULL THEN NULL "
            + "WHEN s.lastFinalBlock <= s.lastSyncedBlock AND s.lastFinalBlock <= :rewindBlock "
            + "THEN s.lastFinalBlock "
            + "WHEN s.lastSyncedBlock <= :rewindBlock THEN s.lastSyncedBlock "
            + "ELSE :rewindBlock END "
            + "WHERE s.chainConfigId = :chainConfigId AND s.indexerType = :indexerType")
    int rewindBlockCursor(
            @Param("chainConfigId") UUID chainConfigId,
            @Param("indexerType") IndexerState.IndexerType indexerType,
            @Param("rewindBlock") Long rewindBlock);

    /** Returns all indexer states with a given status. */
    List<IndexerState> findByStatus(IndexerState.IndexerStatus status);

    /**
     * Returns indexer states that have a given status AND whose last sync timestamp is
     * before the given threshold. Used to detect stale indexers.
     */
    List<IndexerState> findByStatusAndLastSyncedAtBefore(
            IndexerState.IndexerStatus status, Instant threshold);
}
