package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.asset.api.HandoverBlocker;
import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * A register cannot be handed over while a trade of the asset is still in flight (units reserved, payment declared or
 * unresolved): once the register is frozen it can never settle and would be stranded in PAYMENT_UNRESOLVED (9A-07).
 */
@Component
class TradingHandoverBlocker implements HandoverBlocker {
    private final TradeExecutionRepository executions;

    TradingHandoverBlocker(TradeExecutionRepository executions) { this.executions = executions; }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> blocksHandover(UUID assetId) {
        return executions.existsByAssetIdAndSettlementStatusIn(assetId, SettlementStatus.RESERVING)
                ? Optional.of("a secondary-market trade of it is still in flight (reserved / payment declared or "
                        + "unresolved) - settle or fail it first")
                : Optional.empty();
    }
}
