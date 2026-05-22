package de.makibytes.registerwerk.deployment.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VaultRequestRepository extends JpaRepository<VaultRequest, UUID> {

    List<VaultRequest> findByAssetIdAndRequestStatus(UUID assetId, VaultRequestStatus status);

    Optional<VaultRequest> findByAssetIdAndRequestId(UUID assetId, java.math.BigInteger requestId);
}
