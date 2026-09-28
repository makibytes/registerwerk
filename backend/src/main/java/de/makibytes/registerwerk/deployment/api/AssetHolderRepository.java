package de.makibytes.registerwerk.deployment.api;

import de.makibytes.registerwerk.deployment.api.AssetHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetHolderRepository extends JpaRepository<AssetHolder, UUID> {

    // Deliberately unfiltered (includes soft-deleted/removed rows) — see the two remaining call
    // sites (ConfidentialBalanceReconciliationService, indexer.api.HolderDataService), both of
    // which document why they need every row. Every other caller uses the active-only variants
    // below; there is intentionally no unfiltered findByInvestorId/existsByAssetIdAndInvestorId
    // etc. left lying around for a future caller to reach for by mistake.
    Page<AssetHolder> findByAssetId(UUID assetId, Pageable pageable);

    List<AssetHolder> findByAssetId(UUID assetId);

    // No unfiltered findByAssetIdAndWalletAddress: since V11 idx_holder_wallet is unique over
    // active rows only, so one wallet can have an active row plus removed history rows (T3-17).

    Optional<AssetHolder> findByIdAndAssetId(UUID id, UUID assetId);

    // ── Active-only variants (excludes soft-deleted rows, AssetHolder.removedAt) ──────────
    //
    // A removed holder must disappear from compliance/customer-facing listings (register
    // extracts, self-service statements, "my investments", eligibility/access checks). These are
    // the ones nearly every caller should use; findByAssetId above
    // stay unfiltered only for the two reconciliation/audit-facing call sites that documented
    // why they legitimately need every row.

    @Query("SELECT h FROM AssetHolder h WHERE h.assetId = :assetId AND h.removedAt IS NULL")
    Page<AssetHolder> findActiveByAssetId(@Param("assetId") UUID assetId, Pageable pageable);

    @Query("SELECT h FROM AssetHolder h WHERE h.assetId = :assetId AND h.removedAt IS NULL")
    List<AssetHolder> findActiveByAssetId(@Param("assetId") UUID assetId);

    @Query("SELECT h FROM AssetHolder h WHERE h.investorId = :investorId AND h.removedAt IS NULL")
    List<AssetHolder> findActiveByInvestorId(@Param("investorId") UUID investorId);

    @Query("SELECT h FROM AssetHolder h WHERE h.investorId = :investorId AND h.removedAt IS NULL")
    Page<AssetHolder> findActiveByInvestorId(@Param("investorId") UUID investorId, Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(h) > 0 THEN true ELSE false END FROM AssetHolder h "
            + "WHERE h.assetId = :assetId AND h.investorId = :investorId AND h.removedAt IS NULL")
    boolean existsActiveByAssetIdAndInvestorId(@Param("assetId") UUID assetId, @Param("investorId") UUID investorId);

    /** Resolves the caller's own active holding for a self-service register-document download. */
    /**
     * Total nominal over all of the investor's active rows on the asset (an investor may hold
     * several wallets); 0 when none. Use this, not the single-row lookup, for caps and claims.
     */
    @Query("SELECT COALESCE(SUM(h.nominalAmount), 0) FROM AssetHolder h "
            + "WHERE h.investorId = :investorId AND h.assetId = :assetId AND h.removedAt IS NULL")
    java.math.BigDecimal sumActiveNominalByInvestorIdAndAssetId(@Param("investorId") UUID investorId, @Param("assetId") UUID assetId);

    @Query("SELECT h FROM AssetHolder h WHERE h.investorId = :investorId AND h.assetId = :assetId AND h.removedAt IS NULL")
    Optional<AssetHolder> findActiveByInvestorIdAndAssetId(@Param("investorId") UUID investorId, @Param("assetId") UUID assetId);

    @Query("SELECT h FROM AssetHolder h WHERE h.assetId = :assetId AND h.walletAddress = :walletAddress AND h.removedAt IS NULL")
    Optional<AssetHolder> findActiveByAssetIdAndWalletAddress(
            @Param("assetId") UUID assetId, @Param("walletAddress") String walletAddress);

    @Query("SELECT CASE WHEN COUNT(h) > 0 THEN true ELSE false END FROM AssetHolder h "
            + "WHERE h.investorId = :investorId AND h.assetId IN :assetIds AND h.removedAt IS NULL")
    boolean existsActiveByInvestorIdAndAssetIdIn(
            @Param("investorId") UUID investorId, @Param("assetIds") Collection<UUID> assetIds);

    @Query("SELECT CASE WHEN COUNT(h) > 0 THEN true ELSE false END FROM AssetHolder h, Asset a "
            + "WHERE h.assetId = a.id AND h.investorId = :investorId AND a.issuerId = :issuerId AND h.removedAt IS NULL")
    boolean existsActiveByInvestorIdAndIssuerId(
            @Param("investorId") UUID investorId, @Param("issuerId") UUID issuerId);

    @Query("SELECT DISTINCT h.assetId FROM AssetHolder h WHERE h.investorId = :investorId AND h.removedAt IS NULL")
    List<UUID> findDistinctActiveAssetIdsByInvestorId(@Param("investorId") UUID investorId);

    List<AssetHolder> findByWalletAddressIn(Collection<String> walletAddresses);

    @Query("SELECT h FROM AssetHolder h WHERE h.assetId IN :assetIds AND h.walletAddress IN :addresses")
    List<AssetHolder> findByAssetIdInAndWalletAddressIn(
            @Param("assetIds") Collection<UUID> assetIds,
            @Param("addresses") Collection<String> addresses);

    /**
     * §19(2) no. 3 annual statement candidates: single-entry consumer holders
     * whose last statement was issued on/before the cutoff (or never).
     * Keyset-paginated by id; two variants to avoid a nullable id parameter.
     */
    @Query("SELECT h FROM AssetHolder h "
            + "WHERE h.entryType = de.makibytes.registerwerk.deployment.api.EntryType.INDIVIDUAL "
            + "AND h.isConsumer = true "
            + "AND h.removedAt IS NULL "
            + "AND NOT EXISTS (SELECT 1 FROM Asset a WHERE a.id = h.assetId AND a.status = "
            + "de.makibytes.registerwerk.asset.api.AssetStatus.TRANSFERRED_OUT) "
            + "AND (h.lastStatementAt IS NULL OR h.lastStatementAt <= :cutoff) "
            + "ORDER BY h.id")
    List<AssetHolder> findAnnualStatementDueFirst(
            @Param("cutoff") java.time.Instant cutoff,
            Pageable pageable);

    @Query("SELECT h FROM AssetHolder h "
            + "WHERE h.entryType = de.makibytes.registerwerk.deployment.api.EntryType.INDIVIDUAL "
            + "AND h.isConsumer = true "
            + "AND h.removedAt IS NULL "
            + "AND NOT EXISTS (SELECT 1 FROM Asset a WHERE a.id = h.assetId AND a.status = "
            + "de.makibytes.registerwerk.asset.api.AssetStatus.TRANSFERRED_OUT) "
            + "AND (h.lastStatementAt IS NULL OR h.lastStatementAt <= :cutoff) "
            + "AND h.id > :afterId "
            + "ORDER BY h.id")
    List<AssetHolder> findAnnualStatementDueAfter(
            @Param("cutoff") java.time.Instant cutoff,
            @Param("afterId") UUID afterId,
            Pageable pageable);

}
