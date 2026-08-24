package de.makibytes.registerwerk.auth.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RegisterwerkAuthProperties validation")
class RegisterwerkAuthPropertiesTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void rejectsNonPositiveTokenLifetime() {
        RegisterwerkAuthProperties properties = new RegisterwerkAuthProperties();
        properties.setTokenTtlSeconds(0);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("tokenTtlSeconds");
    }

    @Test
    void trimsAndRejectsBlankSigningSecret() {
        RegisterwerkAuthProperties properties = new RegisterwerkAuthProperties();
        properties.setDevSecret("   ");

        assertThat(properties.getDevSecret()).isEmpty();
        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("devSecret");
    }
}
