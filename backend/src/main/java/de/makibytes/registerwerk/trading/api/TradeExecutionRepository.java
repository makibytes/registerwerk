package de.makibytes.registerwerk.trading.api;

import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradeExecutionRepository extends JpaRepository<TradeExecution, UUID> {

    /**
     * Loads an execution with a row-level write lock. Used by settlement to
     * prevent concurrent double-settlement (the buyer holding would be
     * credited twice).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM TradeExecution e WHERE e.id = :id")
    Optional<TradeExecution> findByIdForUpdate(@Param("id") UUID id);

    List<TradeExecution> findByBuyerEntityIdOrSellerEntityIdOrderByCreatedAtDesc(UUID buyerEntityId, UUID sellerEntityId);

    /** Sums reserved units across several statuses — used so a listing's available quantity
     *  correctly excludes units reserved by both PENDING and AWAITING_SELLER_CONFIRMATION
     *  trades, not just one status. */
    @Query("""
        SELECT COALESCE(SUM(e.executedQuantity), 0)
        FROM TradeExecution e
        WHERE e.sellerHolderId = :sellerHolderId
          AND e.settlementStatus IN :settlementStatuses
        """)
    BigDecimal sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(
            @Param("sellerHolderId") UUID sellerHolderId,
            @Param("settlementStatuses") List<SettlementStatus> settlementStatuses);

    /** Input for {@code TradingService.timeoutStuckPendingTrades}. */
    List<TradeExecution> findBySettlementStatusAndCreatedAtBefore(SettlementStatus status, Instant cutoff);

    /** Input for the seller-confirmation half of {@code TradingService.timeoutStuckPendingTrades}
     *  — measured from when the buyer declared payment, not from trade creation. */
    List<TradeExecution> findBySettlementStatusAndPaymentDeclaredAtBefore(SettlementStatus status, Instant cutoff);

    /**
     * Most recent settled trade price for an asset — the reference price surfaced alongside
     * marketplace listings/offers. Previously a trader had nothing to
     * benchmark a quoted listing price against beyond eyeballing it.
     */
    Optional<TradeExecution> findFirstByAssetIdAndSettlementStatusOrderBySettledAtDesc(
            UUID assetId, SettlementStatus settlementStatus);
}
