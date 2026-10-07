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
import java.util.Collection;
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

    /** Input for the timeout job: ids only, each row is then re-loaded under its own lock. */
    @Query("SELECT e.id FROM TradeExecution e WHERE e.settlementStatus = :status AND e.createdAt < :cutoff")
    List<UUID> findIdsBySettlementStatusAndCreatedAtBefore(
            @Param("status") SettlementStatus status, @Param("cutoff") Instant cutoff);

    @Query("SELECT e.id FROM TradeExecution e WHERE e.settlementStatus = :status AND e.paymentDeclaredAt < :cutoff")
    List<UUID> findIdsBySettlementStatusAndPaymentDeclaredAtBefore(
            @Param("status") SettlementStatus status, @Param("cutoff") Instant cutoff);

    List<TradeExecution> findBySettlementStatusOrderByUnresolvedAtAsc(SettlementStatus status);

    long countBySettlementStatus(SettlementStatus status);

    long countBySettlementStatusAndPaymentDeclaredAtBefore(SettlementStatus status, Instant cutoff);

    long countBySettlementStatusAndUnresolvedAtBefore(SettlementStatus status, Instant cutoff);

    /** SRE backlog report: declared-payment trades that were terminally FAILED before PAYMENT_UNRESOLVED existed. */
    @Query("""
        SELECT e FROM TradeExecution e
        WHERE e.settlementStatus = de.makibytes.registerwerk.trading.api.SettlementStatus.FAILED
          AND e.paymentDeclaredAt IS NOT NULL AND e.paymentReference IS NOT NULL
        ORDER BY e.paymentDeclaredAt
        """)
    List<TradeExecution> findFailedAfterDeclaredPayment();

    /** Executions of a seller holding in the given statuses (register removal invalidation). */
    List<TradeExecution> findBySellerHolderIdAndSettlementStatusIn(UUID sellerHolderId, Collection<SettlementStatus> statuses);

    List<TradeExecution> findByAssetIdAndSettlementStatusIn(UUID assetId, Collection<SettlementStatus> statuses);

    boolean existsByAssetIdAndSettlementStatusIn(UUID assetId, Collection<SettlementStatus> statuses);

    @Query("""
        SELECT e FROM TradeExecution e
        WHERE (e.buyerEntityId = :entityId OR e.sellerEntityId = :entityId)
          AND e.settlementStatus IN :statuses
        """)
    List<TradeExecution> findByPartyAndSettlementStatusIn(
            @Param("entityId") UUID entityId, @Param("statuses") Collection<SettlementStatus> statuses);

    long countByListingIdAndSettlementStatusIn(UUID listingId, Collection<SettlementStatus> statuses);

    /** Open (reserving) executions of one buyer - the per-buyer reservation cap. */
    long countByBuyerEntityIdAndSettlementStatusIn(UUID buyerEntityId, Collection<SettlementStatus> statuses);

    long countByBuyerEntityIdAndListingIdAndSettlementStatusIn(
            UUID buyerEntityId, UUID listingId, Collection<SettlementStatus> statuses);

    boolean existsByBuyerEntityIdAndListingIdAndBuyerCooldownUntilAfter(
            UUID buyerEntityId, UUID listingId, Instant now);

    /**
     * Serialises reservation-cap checks per buyer across DIFFERENT listings (the listing row lock
     * only serialises one listing). Transaction-scoped advisory lock; PostgreSQL only.
     */
    @Query(value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended(cast(:key AS text), 0))) t",
            nativeQuery = true)
    long lockBuyerReservations(@Param("key") String key);

    /**
     * H11: serialises every commitment of one {@code (entity, asset)} holding - a trade reservation or
     * settlement, a new listing, and the repo desk's collateral check - on one transaction-scoped
     * advisory lock, so a repo pledge and a sale of the same units cannot both pass their
     * availability check. Callers re-read the committed/pledged quantities AFTER taking it.
     * Lock order in the trading module: holder row first, then this lock; the repo module only takes this one.
     */
    @Query(value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended(cast(:key AS text), 0))) t",
            nativeQuery = true)
    long lockHoldingKey(@Param("key") String key);

    default void lockHolding(UUID entityId, UUID assetId) {
        lockHoldingKey("holding:" + entityId + ":" + assetId);
    }

    /**
     * Most recent settled trade price for an asset — the reference price surfaced alongside
     * marketplace listings/offers. Previously a trader had nothing to
     * benchmark a quoted listing price against beyond eyeballing it.
     */
    Optional<TradeExecution> findFirstByAssetIdAndSettlementStatusOrderBySettledAtDesc(
            UUID assetId, SettlementStatus settlementStatus);

    /** Reference price for the marketplace: latest SETTLED trade between UNRELATED parties (5A-06). */
    Optional<TradeExecution> findFirstByAssetIdAndSettlementStatusAndRelatedPartyFalseOrderBySettledAtDesc(
            UUID assetId, SettlementStatus settlementStatus);

    /** Order-record export (5C-06): every execution created in the window. */
    List<TradeExecution> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            java.time.Instant from, java.time.Instant to);
}
