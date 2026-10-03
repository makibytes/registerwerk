package de.makibytes.registerwerk.asset.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AssetRedemptionBurnRepository extends JpaRepository<AssetRedemptionBurn, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT b FROM AssetRedemptionBurn b WHERE b.id = :id")
    Optional<AssetRedemptionBurn> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    List<AssetRedemptionBurn> findByAssetId(UUID assetId);

    Optional<AssetRedemptionBurn> findByAssetIdAndDeploymentIdAndWalletAddress(UUID assetId, UUID deploymentId, String walletAddress);

    Optional<AssetRedemptionBurn> findByTxId(UUID txId);

    List<AssetRedemptionBurn> findByStatus(AssetRedemptionBurn.Status status);

    long countByStatus(AssetRedemptionBurn.Status status);
}
