package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.api.IndexerState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface IndexerDeploymentCursorRepository extends JpaRepository<IndexerDeploymentCursor, UUID> {

    Optional<IndexerDeploymentCursor> findByDeploymentIdAndIndexerType(
            UUID deploymentId, IndexerState.IndexerType indexerType);

    /** Full-resync support: forget the position of every deployment on one chain. */
    @Modifying
    @Query(value = "UPDATE indexer_deployment_cursor SET cursor_value = NULL, last_synced_at = NULL "
            + "WHERE deployment_id IN (SELECT id FROM asset_deployment WHERE chain_config_id = :chainConfigId)",
            nativeQuery = true)
    int clearPositions(@Param("chainConfigId") UUID chainConfigId);
}
