package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.customer.api.OffboardingObligation;
import de.makibytes.registerwerk.customer.api.OffboardingObligationSource;
import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Trades where cash may already have moved (payment declared or unresolved). PENDING trades and open
 * listings are cancelled automatically by {@code TradeInvalidationService.onEntityOffboarded}.
 */
@Component
class TradingOffboardingObligationSource implements OffboardingObligationSource {

    private static final List<SettlementStatus> CASH_MAY_HAVE_MOVED =
            List.of(SettlementStatus.AWAITING_SELLER_CONFIRMATION, SettlementStatus.PAYMENT_UNRESOLVED);

    private final TradeExecutionRepository executions;

    TradingOffboardingObligationSource(TradeExecutionRepository executions) {
        this.executions = executions;
    }

    @Override
    public List<OffboardingObligation> openObligations(UUID entityId) {
        return executions.findByPartyAndSettlementStatusIn(entityId, CASH_MAY_HAVE_MOVED).stream()
                .map(e -> new OffboardingObligation("TRADE_OPEN", e.getId().toString(),
                        "Trade " + e.getId() + " is " + e.getSettlementStatus() + " (cash may have moved)"))
                .toList();
    }
}
