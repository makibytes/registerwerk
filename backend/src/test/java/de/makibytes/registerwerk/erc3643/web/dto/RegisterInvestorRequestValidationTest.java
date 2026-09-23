package de.makibytes.registerwerk.erc3643.web.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** T1-13: an investor must never be registered with country 0 (unknown). */
class RegisterInvestorRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private RegisterInvestorRequest request(Short country) {
        return new RegisterInvestorRequest("0x1234567890123456789012345678901234567890",
                UUID.randomUUID(), UUID.randomUUID(), country);
    }

    @Test
    @DisplayName("rejects a missing, zero or out-of-range country code")
    void rejectsMissingOrInvalidCountry() {
        for (Short invalid : new Short[] {null, 0, 1000}) {
            assertThat(validator.validate(request(invalid)))
                    .as("countryCode=" + invalid)
                    .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("countryCode"));
        }
    }

    @Test
    @DisplayName("accepts an ISO 3166-1 numeric code")
    void acceptsValidCountry() {
        assertThat(validator.validate(request((short) 276))).isEmpty();
    }
}
