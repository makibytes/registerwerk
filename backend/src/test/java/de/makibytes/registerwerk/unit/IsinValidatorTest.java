package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.shared.IsinValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IsinValidator ISO 6166 unit tests")
class IsinValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "DE0007164600", // SAP SE
            "US0378331005", // Apple Inc.
            "DE000BAY0017", // Bayer AG (alphanumeric NSIN)
            "GB0002634946", // BAE Systems
            "FR0000120271", // TotalEnergies
    })
    @DisplayName("real-world ISINs validate")
    void realIsinsValidate(String isin) {
        assertThat(IsinValidator.isValid(isin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DE0007164601", // wrong check digit
            "DE00071646",   // too short
            "DE00071646001",// too long
            "0E0007164600", // digit in country code
            "DE000716460X", // letter as check digit
            "",
            "   ",
    })
    @DisplayName("malformed or wrong-checksum ISINs are rejected")
    void invalidIsinsRejected(String isin) {
        assertThat(IsinValidator.isValid(isin)).isFalse();
    }

    @Test
    @DisplayName("null is rejected")
    void nullRejected() {
        assertThat(IsinValidator.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("validateOrThrow normalizes lower-case input and trims whitespace")
    void normalizesInput() {
        assertThat(IsinValidator.validateOrThrow("  de0007164600 ")).isEqualTo("DE0007164600");
    }

    @Test
    @DisplayName("validateOrThrow rejects with a descriptive message")
    void throwsDescriptively() {
        assertThatThrownBy(() -> IsinValidator.validateOrThrow("DE0007164601"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 6166");
    }
}
