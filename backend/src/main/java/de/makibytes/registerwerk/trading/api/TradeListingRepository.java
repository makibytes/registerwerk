package de.makibytes.registerwerk.trading.api;

import de.makibytes.registerwerk.trading.api.ListingStatus;
import de.makibytes.registerwerk.trading.api.TradeListing;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradeListingRepository extends JpaRepository<TradeListing, UUID> {

    /**
     * Loads a listing with a row-level write lock ({@code SELECT ... FOR UPDATE}).
     * Must be used by every path that decrements {@code quantityAvailable} —
     * concurrent buys of the same listing would otherwise both pass the
     * availability check and oversell the position.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM TradeListing l WHERE l.id = :id")
    Optional<TradeListing> findByIdForUpdate(@Param("id") UUID id);

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
