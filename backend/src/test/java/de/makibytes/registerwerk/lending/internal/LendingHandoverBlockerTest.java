package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.lending.api.LendingPositionRepository;
import de.makibytes.registerwerk.lending.api.LendingPositionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LendingHandoverBlocker (9A-07)")
class LendingHandoverBlockerTest {

    private final LendingMarketRepository markets = mock(LendingMarketRepository.class);
    private final LendingPositionRepository positions = mock(LendingPositionRepository.class);
    private final LendingHandoverBlocker blocker = new LendingHandoverBlocker(markets, positions);
    private final UUID asset = UUID.randomUUID();

    private LendingMarket market(LendingMarketStatus status) {
        LendingMarket m = new LendingMarket();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        m.setStatus(status);
        return m;
    }

    @Test
    @DisplayName("an open lending position on a live market blocks the handover")
    void openPositionBlocks() {
        LendingMarket m = market(LendingMarketStatus.ACTIVE);
        when(markets.findByCollateralAssetId(asset)).thenReturn(List.of(m));
        when(positions.existsByMarketIdAndStatus(m.getId(), LendingPositionStatus.OPEN)).thenReturn(true);
        assertThat(blocker.blocksHandover(asset)).hasValueSatisfying(r -> assertThat(r).contains("lending position"));
    }

    @Test
    @DisplayName("no open position, or a RETIRED market, does not block")
    void noOpenPositionOrRetiredMarketDoesNotBlock() {
        LendingMarket live = market(LendingMarketStatus.PAUSED);
        LendingMarket retired = market(LendingMarketStatus.RETIRED);
        when(markets.findByCollateralAssetId(asset)).thenReturn(List.of(live, retired));
        assertThat(blocker.blocksHandover(asset)).isEmpty();
        verify(positions, never()).existsByMarketIdAndStatus(retired.getId(), LendingPositionStatus.OPEN);
    }
}
