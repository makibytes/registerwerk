package de.makibytes.registerwerk.deployment.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AssetCouponPaymentRepository extends JpaRepository<AssetCouponPayment, UUID> {

    List<AssetCouponPayment> findByAssetIdOrderByPeriodNo(UUID assetId);

    List<AssetCouponPayment> findByAssetIdAndCouponStatus(UUID assetId, CouponStatus status);

    List<AssetCouponPayment> findByCouponStatusAndScheduledDateLessThanEqual(CouponStatus status, LocalDate date);
}
