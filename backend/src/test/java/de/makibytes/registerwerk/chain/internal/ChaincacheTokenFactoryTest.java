package de.makibytes.registerwerk.chain.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChaincacheTokenFactory")
class ChaincacheTokenFactoryTest {

    @Test
    @DisplayName("mints no token when no secret is configured")
    void bearerFor_noSecret_isEmpty() {
        ChaincacheTokenFactory factory = new ChaincacheTokenFactory("");

        assertThat(factory.bearerFor("http://chaincache:8080")).isEmpty();
    }

    @Test
    @DisplayName("mints a token when a secret is configured")
    void bearerFor_withSecret_mintsToken() {
        ChaincacheTokenFactory factory = new ChaincacheTokenFactory("a-sufficiently-long-test-secret-value");

        Optional<String> token = factory.bearerFor("http://chaincache:8080");

        assertThat(token).isPresent();
        assertThat(token.get()).isNotBlank();
        // A JWT is three base64url segments separated by dots (header.payload.signature).
        assertThat(token.get().split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("returns the same cached token on repeated calls within the TTL")
    void bearerFor_repeatedCalls_returnsCachedToken() {
        ChaincacheTokenFactory factory = new ChaincacheTokenFactory("a-sufficiently-long-test-secret-value");

        String first = factory.bearerFor("http://chaincache:8080").orElseThrow();
        String second = factory.bearerFor("http://chaincache:8080").orElseThrow();

        assertThat(second).isEqualTo(first);
    }
}
