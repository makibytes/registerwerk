package de.makibytes.registerwerk.asset.api;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    Page<Asset> findByIssuerId(UUID issuerId, Pageable pageable);

    List<Asset> findByIssuerId(UUID issuerId);

    Page<Asset> findByStatus(AssetStatus status, Pageable pageable);

    Page<Asset> findByIssuerIdAndStatus(UUID issuerId, AssetStatus status, Pageable pageable);

    Optional<Asset> findByIsin(String isin);

    Optional<Asset> findByAssetNumber(String assetNumber);

    @Query("SELECT a.id FROM Asset a WHERE a.issuerId = :issuerId")
    List<UUID> findIdsByIssuerId(@Param("issuerId") UUID issuerId);

    // ── Holder-sync status (T2-18) ────────────────────────────────────────────
    // Bulk updates on purpose: they do not bump Asset.version, so the sync scheduler never makes
    // a concurrent lifecycle transition fail with an optimistic-lock 409.

    @Query("SELECT a.holderSyncStatus FROM Asset a WHERE a.id = :id")
    Optional<HolderSyncStatus> findHolderSyncStatus(@Param("id") UUID id);

    @Query("SELECT a.holderSyncUnmappedWallets FROM Asset a WHERE a.id = :id")
    Optional<String> findHolderSyncUnmappedWallets(@Param("id") UUID id);

    long countByHolderSyncStatus(HolderSyncStatus status);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Asset a SET a.holderSyncStatus = de.makibytes.registerwerk.asset.api.HolderSyncStatus.OK, "
            + "a.holderSyncBlockedReason = NULL, a.holderSyncUnmappedWallets = NULL, "
            + "a.lastHolderSyncTime = :at, a.lastSuccessfulHolderSyncAt = :at WHERE a.id = :id")
    int markHolderSyncReconciled(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Asset a SET a.holderSyncStatus = de.makibytes.registerwerk.asset.api.HolderSyncStatus.BLOCKED, "
            + "a.holderSyncBlockedReason = :reason, a.holderSyncUnmappedWallets = :wallets, "
            + "a.lastHolderSyncTime = :at WHERE a.id = :id")
    int markHolderSyncBlocked(@Param("id") UUID id, @Param("at") Instant at,
                              @Param("reason") String reason, @Param("wallets") String wallets);
}
