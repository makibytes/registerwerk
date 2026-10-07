package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TradingHandoverBlocker (9A-07)")
class TradingHandoverBlockerTest {

    @Test
    @DisplayName("a trade still in a reserving state (incl. PAYMENT_UNRESOLVED) blocks the handover")
    void inFlightTradeBlocks() {
        TradeExecutionRepository executions = mock(TradeExecutionRepository.class);
        UUID asset = UUID.randomUUID();
        TradingHandoverBlocker blocker = new TradingHandoverBlocker(executions);
        when(executions.existsByAssetIdAndSettlementStatusIn(asset, SettlementStatus.RESERVING)).thenReturn(true);
        assertThat(blocker.blocksHandover(asset)).hasValueSatisfying(r -> assertThat(r).contains("in flight"));
        assertThat(blocker.blocksHandover(UUID.randomUUID())).isEmpty();
        assertThat(SettlementStatus.RESERVING).contains(SettlementStatus.PENDING, SettlementStatus.PAYMENT_UNRESOLVED);
    }
}
