package de.makibytes.registerwerk.webhook.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebhookSigningService unit tests (v1 scheme, 5D-09)")
class WebhookSigningServiceTest {

    private final WebhookSigningService service = new WebhookSigningService();
    private static final UUID DELIVERY = UUID.fromString("11111111-2222-3333-4444-555555555555");

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
    @DisplayName("pinned vector: hex(HMAC-SHA256(secret, timestamp.deliveryId.body)), computed independently with openssl")
    void sign_matchesPinnedVector() {
        // printf '1700000000.11111111-2222-3333-4444-555555555555.{"a":1}' | openssl dgst -sha256 -hmac whsec_test_secret
        assertThat(service.sign(1700000000L, DELIVERY, "{\"a\":1}", "whsec_test_secret"))
                .isEqualTo("3820c2eac74c83686fdc28f19302bd1705a0de334392cbc37623f7892f24d0cf");
    }

    @Test
    @DisplayName("the signature covers timestamp, delivery id, body and secret: mutating any of them invalidates it")
    void sign_coversEveryInput() {
        String base = service.sign(1700000000L, DELIVERY, "{\"a\":1}", "s");
        assertThat(service.sign(1700000001L, DELIVERY, "{\"a\":1}", "s")).isNotEqualTo(base);
        assertThat(service.sign(1700000000L, UUID.randomUUID(), "{\"a\":1}", "s")).isNotEqualTo(base);
        assertThat(service.sign(1700000000L, DELIVERY, "{\"a\":2}", "s")).isNotEqualTo(base);
        assertThat(service.sign(1700000000L, DELIVERY, "{\"a\":1}", "t")).isNotEqualTo(base);
        assertThat(base).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("header carries one v1= value per valid secret (rotation overlap) and never a body-only legacy value")
    void signatureHeader_versionedAndMultiValued() {
        String single = service.signatureHeader(1700000000L, DELIVERY, "b", List.of("new"));
        assertThat(single).startsWith("v1=").doesNotContain(",");

        String both = service.signatureHeader(1700000000L, DELIVERY, "b", List.of("new", "old"));
        String[] parts = both.split(",");
        assertThat(parts).hasSize(2);
        // a receiver holding only the old secret can still verify one of the values
        String expectedOld = "v1=" + service.sign(1700000000L, DELIVERY, "b", "old");
        assertThat(List.of(parts)).contains(expectedOld);
        // constant-time comparison as recommended to receivers
        assertThat(MessageDigest.isEqual(parts[1].getBytes(), expectedOld.getBytes())).isTrue();
    }
}
