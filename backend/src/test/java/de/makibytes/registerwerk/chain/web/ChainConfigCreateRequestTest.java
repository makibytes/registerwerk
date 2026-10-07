package de.makibytes.registerwerk.chain.web;

import de.makibytes.registerwerk.chain.web.dto.ChainConfigCreateRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Wave 5b item 9: an EVM chain must pin its chain id (no migration: validation only). */
@DisplayName("ChainConfigCreateRequest validation")
class ChainConfigCreateRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static ChainConfigCreateRequest request(String chainType, Long chainId) {
        return new ChainConfigCreateRequest("TEST_CHAIN", "Test chain", chainType, "TESTNET", chainId,
                "https://rpc.example.org", null, null, null, null, null, null);
    }

    @Test
    @DisplayName("an EVM chain without chainId is rejected, with a field-level message")
    void evmRequiresChainId() {
        var violations = validator.validate(request("EVM", null));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).contains("chainId is required for EVM");
        assertThat(validator.validate(request("evm", null))).hasSize(1);
        assertThat(validator.validate(request("EVM", 11155111L))).isEmpty();
    }

    @Test
    @DisplayName("non-EVM families have no EIP-155 id and may omit it")
    void otherFamiliesMayOmitIt() {
        assertThat(validator.validate(request("SOLANA", null))).isEmpty();
        assertThat(validator.validate(request("STARKNET", null))).isEmpty();
    }
}
