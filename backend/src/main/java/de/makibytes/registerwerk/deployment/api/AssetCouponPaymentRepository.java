package de.makibytes.registerwerk.deployment.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AssetCouponPaymentRepository extends JpaRepository<AssetCouponPayment, UUID> {

    List<AssetCouponPayment> findByAssetIdOrderByPeriodNo(UUID assetId);

    List<AssetCouponPayment> findByAssetIdAndCouponStatus(UUID assetId, CouponStatus status);

    List<AssetCouponPayment> findByCouponStatusAndScheduledDateLessThanEqual(CouponStatus status, LocalDate date);

    boolean existsByAssetId(UUID assetId);

    /**
     * T3-05: SCHEDULED coupons whose announcement date has been reached — the rows
     * {@code CouponPaymentJob} raises. Legacy rows without an announcement date (created before
     * V12) are returned up to {@code legacyHorizon} by payment date; the job derives their dates.
     */
    @Query("SELECT p FROM AssetCouponPayment p WHERE p.couponStatus = :status AND "
            + "(p.announcementDate <= :today OR (p.announcementDate IS NULL AND p.scheduledDate <= :legacyHorizon))")
    List<AssetCouponPayment> findAnnounceable(@Param("status") CouponStatus status, @Param("today") LocalDate today,
                                              @Param("legacyHorizon") LocalDate legacyHorizon);

    /** Floating-rate coupons that are due but still have no fixing (amount unknown). */
    long countByCouponStatusAndAmountPerUnitIsNullAndScheduledDateLessThanEqual(CouponStatus status, LocalDate date);
}
