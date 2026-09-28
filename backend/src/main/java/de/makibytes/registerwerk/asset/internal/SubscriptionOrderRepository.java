package de.makibytes.registerwerk.asset.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface SubscriptionOrderRepository extends JpaRepository<SubscriptionOrder, UUID> {

    Page<SubscriptionOrder> findByAssetIdOrderBySubmittedAtDesc(UUID assetId, Pageable pageable);

    List<SubscriptionOrder> findByInvestorEntityIdOrderBySubmittedAtDesc(UUID investorEntityId);

    List<SubscriptionOrder> findByAssetIdAndStatusIn(UUID assetId, java.util.Collection<SubscriptionOrder.Status> statuses);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM SubscriptionOrder o WHERE o.id = :id")
    java.util.Optional<SubscriptionOrder> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Sum of everything already spoken for on this asset (ALLOCATED, PAYMENT_CONFIRMED, SETTLED and
     * the legacy CONFIRMED; LAPSED/RELEASED free their capacity again) — used to enforce that new
     * allocations don't push total allocations past {@code Asset.issueSize} when it's set.
     */
    @Query("""
        select coalesce(sum(o.allocatedAmount), 0) from SubscriptionOrder o
        where o.assetId = :assetId and o.status in (
            de.makibytes.registerwerk.asset.internal.SubscriptionOrder.Status.ALLOCATED,
            de.makibytes.registerwerk.asset.internal.SubscriptionOrder.Status.PAYMENT_CONFIRMED,
            de.makibytes.registerwerk.asset.internal.SubscriptionOrder.Status.SETTLED,
            de.makibytes.registerwerk.asset.internal.SubscriptionOrder.Status.CONFIRMED)
        """)
    BigDecimal sumAllocated(@Param("assetId") UUID assetId);

    /**
     * The investor's allocations that are not (yet) in the register: ALLOCATED and PAYMENT_CONFIRMED.
     * Added to the active holding for the holding-cap check, so parallel allocations cannot each pass
     * the cap on their own (T3-08).
     */
    @Query("""
        select coalesce(sum(o.allocatedAmount), 0) from SubscriptionOrder o
        where o.assetId = :assetId and o.investorEntityId = :investorEntityId and o.status in (
            de.makibytes.registerwerk.asset.internal.SubscriptionOrder.Status.ALLOCATED,
            de.makibytes.registerwerk.asset.internal.SubscriptionOrder.Status.PAYMENT_CONFIRMED)
        """)
    BigDecimal sumOpenAllocatedForInvestor(@Param("assetId") UUID assetId,
                                           @Param("investorEntityId") UUID investorEntityId);

    @Query("""
        select o from SubscriptionOrder o
        where o.status = de.makibytes.registerwerk.asset.internal.SubscriptionOrder.Status.ALLOCATED
          and o.allocationExpiresAt is not null and o.allocationExpiresAt < :now
        order by o.allocationExpiresAt
        """)
    List<SubscriptionOrder> findExpiredAllocations(@Param("now") java.time.Instant now, Pageable pageable);
}
