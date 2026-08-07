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

    /**
     * Sum of everything already spoken for on this asset (ALLOCATED, pending investor
     * confirmation, plus already-CONFIRMED) — used to enforce that new allocations don't push
     * total allocations past {@code Asset.issueSize} when it's set.
     */
    @Query("""
        select coalesce(sum(o.allocatedAmount), 0) from SubscriptionOrder o
        where o.assetId = :assetId and o.status in (
            de.makibytes.registerwerk.asset.internal.SubscriptionOrder.Status.ALLOCATED,
            de.makibytes.registerwerk.asset.internal.SubscriptionOrder.Status.CONFIRMED)
        """)
    BigDecimal sumAllocated(@Param("assetId") UUID assetId);
}
