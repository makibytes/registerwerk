package de.makibytes.registerwerk.asset.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetTokenAdminGrantRepository extends JpaRepository<AssetTokenAdminGrant, UUID> {

    /**
     * Runtime hot-path check for {@code AssetAccessChecker.canForceAdmin} — an ACTIVE,
     * non-expired grant that is either scoped to this asset or entity-wide ({@code asset_id
     * IS NULL}). Called on every forcedTransfer/forcedApprove/forceBurn request.
     */
    @Query("SELECT CASE WHEN COUNT(g) > 0 THEN true ELSE false END FROM AssetTokenAdminGrant g " +
           "WHERE g.entityId = :entityId AND g.status = 'ACTIVE' " +
           "AND (g.assetId = :assetId OR g.assetId IS NULL) " +
           "AND (g.expiresAt IS NULL OR g.expiresAt > :now)")
    boolean existsActiveForEntityAndAsset(@Param("entityId") UUID entityId,
                                          @Param("assetId") UUID assetId,
                                          @Param("now") Instant now);

    List<AssetTokenAdminGrant> findByAssetIdAndStatus(UUID assetId, AssetTokenAdminGrant.Status status);

    List<AssetTokenAdminGrant> findByEntityIdAndAssetIdIsNullAndStatus(UUID entityId, AssetTokenAdminGrant.Status status);

    Optional<AssetTokenAdminGrant> findByIdAndAssetId(UUID id, UUID assetId);

    Optional<AssetTokenAdminGrant> findByIdAndEntityIdAndAssetIdIsNull(UUID id, UUID entityId);

    /** All of an entity's grants (asset-scoped and entity-wide) in a given status — used by
     *  {@code CustomerOffboardingAssetListener} to revoke everything on customer exit. */
    List<AssetTokenAdminGrant> findByEntityIdAndStatus(UUID entityId, AssetTokenAdminGrant.Status status);

    /** All grants with the given status — used for the global compliance work-queue. */
    List<AssetTokenAdminGrant> findByStatusOrderByCreatedAtDesc(AssetTokenAdminGrant.Status status);

    @Query("SELECT g FROM AssetTokenAdminGrant g WHERE g.status = 'ACTIVE' " +
           "AND g.expiresAt IS NOT NULL AND g.expiresAt <= :now")
    List<AssetTokenAdminGrant> findExpiredActive(@Param("now") Instant now);
}
