package de.makibytes.registerwerk.asset.api;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    Page<Asset> findByIssuerId(UUID issuerId, Pageable pageable);

    Page<Asset> findByStatus(AssetStatus status, Pageable pageable);

    Page<Asset> findByIssuerIdAndStatus(UUID issuerId, AssetStatus status, Pageable pageable);

    Optional<Asset> findByIsin(String isin);

    @Query("SELECT a.id FROM Asset a WHERE a.issuerId = :issuerId")
    List<UUID> findIdsByIssuerId(@Param("issuerId") UUID issuerId);
}
