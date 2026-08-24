package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent;
import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.events.LendingCollateralReconciliationNeededEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the forced-transfer/force-burn to lending-market desync detector.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ForcedTransferReconciliationListener unit tests")
class ForcedTransferReconciliationListenerTest {

    @Mock private LendingMarketRepository marketRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ForcedTransferReconciliationListener listener;

    private static final String MARKET_ADDRESS = "0x" + "aa".repeat(20);

    @BeforeEach
    void setUp() {
        listener = new ForcedTransferReconciliationListener(marketRepository, eventPublisher);
    }

    private static LendingMarket market(UUID id) {
        LendingMarket m = new LendingMarket();
        m.setId(id);
        m.setMarketAddress(MARKET_ADDRESS);
        return m;
    }

    @Test
    @DisplayName("publishes a reconciliation-needed event when forcedTransfer's 'from' matches a known market")
    void publishesWhenFromMatchesKnownMarket() {
        UUID marketId = UUID.randomUUID();
        when(marketRepository.findByMarketAddressIgnoreCase(MARKET_ADDRESS)).thenReturn(Optional.of(market(marketId)));

        listener.onTokenAdminAction(new TokenAdminActionEvent(UUID.randomUUID(), "forcedTransfer", UUID.randomUUID(),
                "REGISTRY_ADMIN", Map.of("from", MARKET_ADDRESS, "to", "0xdest", "value", "500", "legalBasis", "§24 eWpG")));

        ArgumentCaptor<LendingCollateralReconciliationNeededEvent> captor =
                ArgumentCaptor.forClass(LendingCollateralReconciliationNeededEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().marketId()).isEqualTo(marketId);
        assertThat(captor.getValue().marketAddress()).isEqualTo(MARKET_ADDRESS);
        assertThat(captor.getValue().tokenAdminMethod()).isEqualTo("forcedTransfer");
        assertThat(captor.getValue().toAddress()).isEqualTo("0xdest");
        assertThat(captor.getValue().amount()).isEqualTo("500");
    }

    @Test
    @DisplayName("publishes for forceBurn too, using the 'amount' key rather than 'value'")
    void publishesForForceBurnUsingAmountKey() {
        UUID marketId = UUID.randomUUID();
        when(marketRepository.findByMarketAddressIgnoreCase(MARKET_ADDRESS)).thenReturn(Optional.of(market(marketId)));

        listener.onTokenAdminAction(new TokenAdminActionEvent(UUID.randomUUID(), "forceBurnSingle", UUID.randomUUID(),
                "REGISTRY_ADMIN", Map.of("from", MARKET_ADDRESS, "id", "0", "amount", "42", "legalBasis", "court order")));

        ArgumentCaptor<LendingCollateralReconciliationNeededEvent> captor =
                ArgumentCaptor.forClass(LendingCollateralReconciliationNeededEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().amount()).isEqualTo("42");
    }

    @Test
    @DisplayName("does not publish when 'from' does not match any known lending market")
    void doesNotPublishWhenNoMarketMatches() {
        when(marketRepository.findByMarketAddressIgnoreCase(any())).thenReturn(Optional.empty());

        listener.onTokenAdminAction(new TokenAdminActionEvent(UUID.randomUUID(), "forcedTransfer", UUID.randomUUID(),
                "REGISTRY_ADMIN", Map.of("from", "0xsomeoneelse", "to", "0xdest", "value", "500", "legalBasis", "x")));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("ignores unrelated token-admin actions (pause, whitelist, mint, etc.)")
    void ignoresUnrelatedTokenAdminActions() {
        listener.onTokenAdminAction(new TokenAdminActionEvent(UUID.randomUUID(), "pause", UUID.randomUUID(),
                "REGISTRY_ADMIN", Map.of()));

        verify(marketRepository, never()).findByMarketAddressIgnoreCase(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
