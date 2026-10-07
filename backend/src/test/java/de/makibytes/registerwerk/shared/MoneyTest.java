package de.makibytes.registerwerk.shared;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Wave 5a: the one minor-unit / rounding policy (HALF_UP, fail closed on an unknown currency). */
class MoneyTest {

    @Test
    void roundsHalfUpToTheMinorUnit_halfCentCasesAreExplicit() {
        // HALF_EVEN (the old trading / corporate-action policy) gave 2.34, 0.02, 0.02, 0.00 for the first four
        assertThat(Money.round(new BigDecimal("2.345"), "EUR")).isEqualByComparingTo("2.35");
        assertThat(Money.round(new BigDecimal("0.025"), "EUR")).isEqualByComparingTo("0.03");
        assertThat(Money.round(new BigDecimal("0.005"), "EUR")).isEqualByComparingTo("0.01");
        assertThat(Money.round(new BigDecimal("0.015"), "EUR")).isEqualByComparingTo("0.02");
        assertThat(Money.round(new BigDecimal("2.355"), "EUR")).isEqualByComparingTo("2.36");
        assertThat(Money.round(new BigDecimal("2.344999"), "EUR")).isEqualByComparingTo("2.34");
        assertThat(Money.round(new BigDecimal("-2.345"), "EUR")).as("half rounds away from zero").isEqualByComparingTo("-2.35");
        assertThat(Money.round(new BigDecimal("333.333333333333333330"), "EUR")).isEqualByComparingTo("333.33");
        assertThat(Money.round(new BigDecimal("2.5"), "JPY")).as("JPY has no minor unit").isEqualByComparingTo("3");
        assertThat(Money.round(new BigDecimal("2.5"), "JPY").scale()).isZero();
        assertThat(Money.round(new BigDecimal("0.0000125"), 6)).as("explicit scale, e.g. a stablecoin rail")
                .isEqualByComparingTo("0.000013");
        assertThat(Money.MODE).isEqualTo(java.math.RoundingMode.HALF_UP);
    }

    @Test
    void minorUnitsFollowIso4217AndAreCaseInsensitive() {
        assertThat(Money.minorUnits("EUR")).isEqualTo(2);
        assertThat(Money.minorUnits(" eur ")).isEqualTo(2);
        assertThat(Money.minorUnits("JPY")).isZero();
        assertThat(Money.minorUnits("KWD")).isEqualTo(3);
    }

    @Test
    void unknownMissingOrMinorUnitlessCurrencyFailsClosed() {
        assertThatThrownBy(() -> Money.minorUnits("XXQ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ISO 4217 currency");
        assertThatThrownBy(() -> Money.minorUnits(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.minorUnits("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.minorUnits("XXX")).as("pseudo currency without a minor unit")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.round(BigDecimal.ONE, (String) null)).isInstanceOf(IllegalArgumentException.class);
    }
}
