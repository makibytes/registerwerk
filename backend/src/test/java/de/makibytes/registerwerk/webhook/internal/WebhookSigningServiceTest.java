package de.makibytes.registerwerk.webhook.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebhookSigningService unit tests (Track 6-1)")
class WebhookSigningServiceTest {

    private final WebhookSigningService service = new WebhookSigningService();

    @Test
    @DisplayName("generateSecret produces non-blank, distinct values")
    void generateSecret_producesDistinctValues() {
        String a = service.generateSecret();
        String b = service.generateSecret();

        assertThat(a).isNotBlank();
        assertThat(b).isNotBlank();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("sign is deterministic for the same payload and secret")
    void sign_isDeterministic() {
        String sig1 = service.sign("{\"a\":1}", "secret123");
        String sig2 = service.sign("{\"a\":1}", "secret123");

        assertThat(sig1).isEqualTo(sig2);
        assertThat(sig1).matches("^[0-9a-f]{64}$"); // hex-encoded SHA-256 digest length
    }

    @Test
    @DisplayName("sign differs for different secrets given the same payload")
    void sign_differsByDifferentSecret() {
        String sig1 = service.sign("payload", "secretA");
        String sig2 = service.sign("payload", "secretB");

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("sign differs for different payloads given the same secret")
    void sign_differsByDifferentPayload() {
        String sig1 = service.sign("payloadA", "secret");
        String sig2 = service.sign("payloadB", "secret");

        assertThat(sig1).isNotEqualTo(sig2);
    }
}
