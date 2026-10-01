package de.makibytes.registerwerk.trading.internal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void roundsHalfEvenToTheMinorUnit() {
        assertThat(Money.round(new BigDecimal("2.345"), 2)).isEqualByComparingTo("2.34");
        assertThat(Money.round(new BigDecimal("2.355"), 2)).isEqualByComparingTo("2.36");
        assertThat(Money.round(new BigDecimal("333.333333333333333330"), Money.minorUnits("EUR"))).isEqualByComparingTo("333.33");
    }

    @Test
    void minorUnitsFollowIso4217() {
        assertThat(Money.minorUnits("EUR")).isEqualTo(2);
        assertThat(Money.minorUnits("JPY")).isZero();
        assertThatThrownBy(() -> Money.minorUnits("XXQ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stablecoinScaleIsCappedAtSix() {
        assertThat(Money.stablecoinScale(18)).isEqualTo(6);
        assertThat(Money.stablecoinScale(2)).isEqualTo(2);
        assertThat(Money.stablecoinScale(null)).isEqualTo(6);
    }

    @Test
    void csvEscapeQuotesAndNeutralisesFormulas() {
        assertThat(OrderHistoryExportService.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(OrderHistoryExportService.escape("=SUM(1)")).isEqualTo("'=SUM(1)");
    }
}
