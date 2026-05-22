package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.indexer.api.IndexerState;
import org.springframework.data.jpa.repository.JpaRepository;

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

    /** Returns all indexer states with a given status. */
    List<IndexerState> findByStatus(IndexerState.IndexerStatus status);

    /**
     * Returns indexer states that have a given status AND whose last sync timestamp is
     * before the given threshold. Used to detect stale indexers.
     */
    List<IndexerState> findByStatusAndLastSyncedAtBefore(
            IndexerState.IndexerStatus status, Instant threshold);
}
