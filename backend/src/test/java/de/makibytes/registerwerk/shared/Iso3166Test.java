package de.makibytes.registerwerk.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class Iso3166Test {

    @Test
    @DisplayName("maps alpha-2 to ISO 3166-1 numeric, case-insensitively")
    void mapsKnownCodes() {
        assertThat(Iso3166.numericFromAlpha2("DE")).contains(276);
        assertThat(Iso3166.numericFromAlpha2("us")).contains(840);
        assertThat(Iso3166.numericFromAlpha2(" af ")).contains(4);
        assertThat(Iso3166.numericFromAlpha2("XX")).isEmpty();
        assertThat(Iso3166.numericFromAlpha2(null)).isEmpty();
    }

    @Test
    @DisplayName("covers every JDK ISO country with a distinct code in 1..999")
    void coversEveryJdkCountry() {
        assertThat(Locale.getISOCountries()).allSatisfy(cc ->
                assertThat(Iso3166.numericFromAlpha2(cc)).as(cc).hasValueSatisfying(n ->
                        assertThat(n).isBetween(1, 999)));
        assertThat(Arrays.stream(Locale.getISOCountries())
                .map(cc -> Iso3166.numericFromAlpha2(cc).orElseThrow()).distinct().count())
                .isEqualTo(Locale.getISOCountries().length);
    }
}
