package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.domain.enums.AssetStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    Page<Asset> findByIssuerId(UUID issuerId, Pageable pageable);

    Page<Asset> findByStatus(AssetStatus status, Pageable pageable);

    Page<Asset> findByIssuerIdAndStatus(UUID issuerId, AssetStatus status, Pageable pageable);

    Optional<Asset> findByIsin(String isin);
}
