package de.makibytes.registerwerk.trading.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradeCurrencyScaleTest {

    @Test
    void stablecoinScaleIsCappedAtSix() {
        assertThat(TradeCurrencyPolicy.stablecoinScale(18)).isEqualTo(6);
        assertThat(TradeCurrencyPolicy.stablecoinScale(2)).isEqualTo(2);
        assertThat(TradeCurrencyPolicy.stablecoinScale(null)).isEqualTo(6);
    }

    @Test
    void csvEscapeQuotesAndNeutralisesFormulas() {
        assertThat(OrderHistoryExportService.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(OrderHistoryExportService.escape("=SUM(1)")).isEqualTo("'=SUM(1)");
    }
}
