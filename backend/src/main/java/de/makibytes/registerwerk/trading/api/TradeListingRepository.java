package de.makibytes.registerwerk.trading.api;

import de.makibytes.registerwerk.trading.internal.ListingStatus;
import de.makibytes.registerwerk.trading.api.TradeListing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TradeListingRepository extends JpaRepository<TradeListing, UUID> {

    List<TradeListing> findBySellerEntityIdOrderByCreatedAtDesc(UUID sellerEntityId);

    List<TradeListing> findByStatusInOrderByCreatedAtDesc(Collection<ListingStatus> statuses);

    @Query("""
        SELECT COALESCE(SUM(l.quantityAvailable), 0)
        FROM TradeListing l
        WHERE l.sellerHolderId = :sellerHolderId
          AND l.status IN :statuses
        """)
    BigDecimal sumQuantityAvailableBySellerHolderIdAndStatusIn(
            @Param("sellerHolderId") UUID sellerHolderId,
            @Param("statuses") Collection<ListingStatus> statuses);
}
