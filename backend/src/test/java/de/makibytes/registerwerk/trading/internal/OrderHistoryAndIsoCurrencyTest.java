package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.api.TradeListing;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderHistoryAndIsoCurrencyTest {

    @Test
    void exportSortsOnTheInstantNotItsIsoString() {
        TradeListingRepository listings = mock(TradeListingRepository.class);
        TradeExecutionRepository executions = mock(TradeExecutionRepository.class);
        Instant whole = Instant.parse("2026-01-01T10:00:00Z");      // toString drops the zero fraction: "...:00Z"
        Instant later = Instant.parse("2026-01-01T10:00:00.123Z");  // "...:00.123Z" sorts BEFORE "...:00Z" as a string
        TradeListing a = listing(UUID.fromString("00000000-0000-0000-0000-00000000000a"), later);
        TradeListing b = listing(UUID.fromString("00000000-0000-0000-0000-00000000000b"), whole);
        TradeListing c = listing(UUID.fromString("00000000-0000-0000-0000-00000000000c"), whole);
        when(listings.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(c, a, b));
        when(executions.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());

        String csv = new String(new OrderHistoryExportService(listings, executions).exportCsv(whole, later.plusSeconds(1)),
                StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertThat(lines[1]).contains("0000000000b");
        assertThat(lines[2]).contains("0000000000c");   // same instant: tie-break by id
        assertThat(lines[3]).contains("0000000000a");
    }

    @Test
    void nonIsoRailCurrencyIsNotWrittenAsCcy() {
        TradeExecution e = new TradeExecution();
        e.setSellerEntityId(UUID.randomUUID());
        e.setBuyerEntityId(UUID.randomUUID());
        e.setUnitPrice(BigDecimal.ONE);
        e.setTotalPrice(BigDecimal.ONE);
        e.setCurrency("USDC");
        e.setPaymentRailCode("USDC-ETH");
        String xml = new String(Iso20022SettlementConfirmationRenderer.render(UUID.randomUUID(), e, null, null));
        assertThat(xml).doesNotContain("Ccy=").contains("USDC").contains("not an ISO 4217 code");
    }

    private static TradeListing listing(UUID id, Instant createdAt) {
        TradeListing l = new TradeListing();
        ReflectionTestUtils.setField(l, "id", id);
        ReflectionTestUtils.setField(l, "createdAt", createdAt);
        return l;
    }
}
