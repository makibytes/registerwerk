package de.makibytes.registerwerk.indexer.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChainDriftEventRepository extends JpaRepository<ChainDriftEvent, UUID> {

    Page<ChainDriftEvent> findByStatusOrderByDetectedAtDesc(ChainDriftStatus status, Pageable pageable);

    Page<ChainDriftEvent> findByAssetIdOrderByDetectedAtDesc(UUID assetId, Pageable pageable);

    long countByStatus(ChainDriftStatus status);
}
