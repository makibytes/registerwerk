package de.makibytes.registerwerk.indexer.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChainDriftEventRepository extends JpaRepository<ChainDriftEvent, UUID> {

    Page<ChainDriftEvent> findByStatusOrderByDetectedAtDesc(ChainDriftStatus status, Pageable pageable);

    /** OPEN rows only count once a divergence has survived a second consecutive detection run —
     *  see {@code ChainDriftDetectionJob}'s confirm-on-reconfirmation flow. Used in place of the
     *  plain by-status finder whenever status == OPEN, so a same-run "candidate" that hasn't been
     *  reconfirmed yet never appears in the operator work queue. */
    Page<ChainDriftEvent> findByStatusAndConfirmedTrueOrderByDetectedAtDesc(ChainDriftStatus status, Pageable pageable);

    Page<ChainDriftEvent> findByAssetIdOrderByDetectedAtDesc(UUID assetId, Pageable pageable);

    long countByStatus(ChainDriftStatus status);

    long countByStatusAndConfirmedTrue(ChainDriftStatus status);
}
