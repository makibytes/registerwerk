package de.makibytes.registerwerk.trading.api;

import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface TradeExecutionRepository extends JpaRepository<TradeExecution, UUID> {

    List<TradeExecution> findByBuyerEntityIdOrSellerEntityIdOrderByCreatedAtDesc(UUID buyerEntityId, UUID sellerEntityId);

    @Query("""
        SELECT COALESCE(SUM(e.executedQuantity), 0)
        FROM TradeExecution e
        WHERE e.sellerHolderId = :sellerHolderId
          AND e.settlementStatus = :settlementStatus
        """)
    BigDecimal sumExecutedQuantityBySellerHolderIdAndSettlementStatus(
            @Param("sellerHolderId") UUID sellerHolderId,
            @Param("settlementStatus") SettlementStatus settlementStatus);
}
