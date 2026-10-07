package de.makibytes.registerwerk.corporateactions.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CorporateActionRepository extends JpaRepository<CorporateAction, UUID> {

    List<CorporateAction> findByAssetIdAndStatus(UUID assetId, CorporateAction.Status status);

    List<CorporateAction> findByAssetId(UUID assetId);

    List<CorporateAction> findByStatus(CorporateAction.Status status);

    long countByStatus(CorporateAction.Status status);

    /** T3-02: SETTLED actions kept open because nominee-pool entitlements are unresolved. */
    long countByHeldOutstandingTrue();

    /** T3-05: has a CALL for this asset settled (so the bond is retired, not redeemed at maturity)? */
    @Query("SELECT CASE WHEN COUNT(ca) > 0 THEN true ELSE false END FROM CorporateAction ca "
            + "WHERE ca.assetId = :assetId AND ca.actionType = 'CALL' AND ca.status IN ('SETTLED','CLOSED')")
    boolean existsSettledCallForAsset(@Param("assetId") UUID assetId);

    /** Idempotency guard: has a corporate action already been created for this coupon payment? */
    boolean existsByCouponPaymentId(UUID couponPaymentId);

    /** Idempotency guard for {@code BondMaturityJob}: has a redemption action already been
     *  raised for this asset (in any non-terminal-failure state)? */
    @Query("SELECT CASE WHEN COUNT(ca) > 0 THEN true ELSE false END FROM CorporateAction ca "
            + "WHERE ca.assetId = :assetId AND ca.actionType = 'REDEMPTION' AND ca.status <> 'CANCELLED'")
    boolean existsActiveRedemptionForAsset(@Param("assetId") UUID assetId);

    /** REDEMPTION actions whose payment date has passed without settling — the OVERDUE/DEFAULTED
     *  detection input for {@code BondMaturityJob}, which applies the bond's principal grace period
     *  on top (T3-05). */
    @Query("SELECT ca FROM CorporateAction ca WHERE ca.assetId = :assetId AND ca.actionType = 'REDEMPTION' "
            + "AND ca.status NOT IN ('SETTLED','CLOSED','CANCELLED') AND ca.paymentDate < :today")
    List<CorporateAction> findOverdueRedemptions(@Param("assetId") UUID assetId, @Param("today") LocalDate today);

    /** 9A-04R: past-due COUPON / REDEMPTION actions that wait for the OPERATOR (issuer attested but not confirmed, both
     *  signed but not dispatched, or AWAITING_SETTLEMENT) and that the system is not holding back itself. These are
     *  never counted toward OVERDUE / DEFAULTED / MISSED; the gauge makes the operator's lag visible instead. */
    @Query("SELECT COUNT(ca) FROM CorporateAction ca WHERE ca.actionType IN ('COUPON','REDEMPTION') "
            + "AND ca.paymentDate < :today AND (ca.settlementHoldReason IS NULL OR ca.settlementHoldReason = '') "
            + "AND (ca.status = 'AWAITING_SETTLEMENT' OR (ca.status = 'COMPUTED' AND ca.issuerAttestedAt IS NOT NULL))")
    long countOperatorSideOverdue(@Param("today") LocalDate today);

    /** COUPON actions whose payment date has passed without settling — the OVERDUE/MISSED coupon
     *  detection input for {@code CorporateActionService}, which applies the bond's interest grace
     *  period on top (T3-05; mirrors {@link #findOverdueRedemptions}). */
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
     *
     * <p>Also excludes PROPOSED/REJECTED — an issuer-proposed DIVIDEND/SPLIT/CALL that hasn't
     * been reviewed yet (or was rejected) must never be picked up for settlement dispatch just
     * because a client-supplied {@code paymentDate} happens to be in the past; only an
     * operator-approved (→ ANNOUNCED and beyond) action is a register fact.
     *
     * <p>Also excludes SNAPSHOT_BLOCKED (T2-18): with no entitlement snapshot there is nothing a
     * settlement could correctly pay.
     *
     * <p>Only COMPUTED qualifies (T3-06): the snapshot now runs strictly after the record date, so an
     * action whose payment date equals its record date is still ANNOUNCED on the payment date — it
     * must wait for its entitlements rather than dispatch with none.
     */
    @Query("SELECT ca FROM CorporateAction ca WHERE ca.status = 'COMPUTED' AND ca.paymentDate <= :date")
    List<CorporateAction> findDueForSettlement(@Param("date") LocalDate date);

    /** Includes SNAPSHOT_BLOCKED (T2-18): a refused snapshot is retried on every daily run.
     *  Strictly after the record date (T3-06): entitlements are fixed as of the END of the record
     *  date, so the snapshot cannot be taken before that day is over. */
    @Query("SELECT ca FROM CorporateAction ca WHERE ca.status IN ('ANNOUNCED','SNAPSHOT_BLOCKED') AND ca.recordDate < :today")
    List<CorporateAction> findReadyToCompute(@Param("today") LocalDate today);

    /** The operator's proposal review queue — every issuer-submitted proposal awaiting
     *  approve/reject, oldest first so nothing sits unreviewed indefinitely by accident. */
    List<CorporateAction> findByStatusOrderByCreatedAtAsc(CorporateAction.Status status);

    /** Backs both the issuer's own list view and the investor's {@code Me} view — each scoped
     *  further (issuer: by assetId + ownership; investor: excluding PROPOSED/REJECTED) by the
     *  caller, not here. */
    List<CorporateAction> findByAssetIdAndStatusIn(UUID assetId, Collection<CorporateAction.Status> statuses);

    /**
     * Resolves the token standard of the asset linked to a corporate action without
     * importing from the {@code asset} module (avoids the asset ↔ blockchain Modulith cycle).
     * Returns null if the asset is not found.
     */
    @Query(value = "SELECT a.token_standard FROM asset a INNER JOIN corporate_action ca ON ca.asset_id = a.id WHERE ca.id = :corporateActionId", nativeQuery = true)
    String findTokenStandardByCorpAction(@Param("corporateActionId") UUID corporateActionId);
}
