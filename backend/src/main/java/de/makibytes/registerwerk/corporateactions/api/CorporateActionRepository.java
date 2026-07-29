package de.makibytes.registerwerk.corporateactions.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface CorporateActionRepository extends JpaRepository<CorporateAction, UUID> {

    List<CorporateAction> findByAssetIdAndStatus(UUID assetId, CorporateAction.Status status);

    List<CorporateAction> findByAssetId(UUID assetId);

    List<CorporateAction> findByStatus(CorporateAction.Status status);

    /** Idempotency guard: has a corporate action already been created for this coupon payment? */
    boolean existsByCouponPaymentId(UUID couponPaymentId);

    /** Idempotency guard for {@code BondMaturityJob}: has a redemption action already been
     *  raised for this asset (in any non-terminal-failure state)? */
    @Query("SELECT CASE WHEN COUNT(ca) > 0 THEN true ELSE false END FROM CorporateAction ca "
            + "WHERE ca.assetId = :assetId AND ca.actionType = 'REDEMPTION' AND ca.status <> 'CANCELLED'")
    boolean existsActiveRedemptionForAsset(@Param("assetId") UUID assetId);

    /** REDEMPTION actions whose payment date has passed without settling — the bond-default
     *  detection input for {@code BondMaturityJob}. */
    @Query("SELECT ca FROM CorporateAction ca WHERE ca.assetId = :assetId AND ca.actionType = 'REDEMPTION' "
            + "AND ca.status NOT IN ('SETTLED','CLOSED','CANCELLED') AND ca.paymentDate < :today")
    List<CorporateAction> findOverdueRedemptions(@Param("assetId") UUID assetId, @Param("today") LocalDate today);

    /** COUPON actions whose payment date has passed without settling — the missed-coupon
     *  detection input for {@code CorporateActionService} (mirrors {@link #findOverdueRedemptions}). */
    @Query("SELECT ca FROM CorporateAction ca WHERE ca.actionType = 'COUPON' AND ca.couponPaymentId IS NOT NULL "
            + "AND ca.status NOT IN ('SETTLED','CLOSED','CANCELLED') AND ca.paymentDate < :today")
    List<CorporateAction> findOverdueCoupons(@Param("today") LocalDate today);

    /**
     * Excludes AWAITING_SETTLEMENT (not just SETTLED/CLOSED/CANCELLED): an action whose async
     * settlement dispatch is slow, failed, or never completed would otherwise stay
     * AWAITING_SETTLEMENT with paymentDate still &lt;= today, so the next day's cron run would
     * pick it up here again and re-dispatch settlement a second time — a real double-payment
     * risk, not hypothetical. A stuck AWAITING_SETTLEMENT action requires deliberate operator
     * action (see {@code CorporateActionAdminController}) instead of a silent automatic retry.
     */
    @Query("SELECT ca FROM CorporateAction ca WHERE ca.status NOT IN ('SETTLED','CLOSED','CANCELLED','AWAITING_SETTLEMENT') AND ca.paymentDate <= :date")
    List<CorporateAction> findDueForSettlement(@Param("date") LocalDate date);

    @Query("SELECT ca FROM CorporateAction ca WHERE ca.status = 'ANNOUNCED' AND ca.recordDate <= :today")
    List<CorporateAction> findReadyToCompute(@Param("today") LocalDate today);

    /**
     * Resolves the token standard of the asset linked to a corporate action without
     * importing from the {@code asset} module (avoids the asset ↔ blockchain Modulith cycle).
     * Returns null if the asset is not found.
     */
    @Query(value = "SELECT a.token_standard FROM asset a INNER JOIN corporate_action ca ON ca.asset_id = a.id WHERE ca.id = :corporateActionId", nativeQuery = true)
    String findTokenStandardByCorpAction(@Param("corporateActionId") UUID corporateActionId);
}
