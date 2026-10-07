package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebhookSecretCipher — envelope encryption at rest (5D-09)")
class WebhookSecretCipherTest {

    /** Trivial reversible KEK for the test; the real providers are KMS/HSM/env-var backed. */
    static final KekProvider XOR_KEK = new KekProvider() {
        @Override public String name() { return "TEST"; }
        @Override public byte[] wrap(byte[] dek) { return xor(dek); }
        @Override public byte[] unwrap(byte[] wrapped) { return xor(wrapped); }
        private byte[] xor(byte[] in) {
            byte[] out = in.clone();
            for (int i = 0; i < out.length; i++) out[i] ^= (byte) 0x5A;
            return out;
        }
    };

    private final WebhookSecretCipher cipher = new WebhookSecretCipher(XOR_KEK);
    private final UUID subscriptionId = UUID.randomUUID();

    @Test
    @DisplayName("stored value is prefixed enc:v1:, contains no plaintext and round-trips")
    void roundTrip() {
        String stored = cipher.encrypt("my-secret-value", subscriptionId);
        assertThat(stored).startsWith("enc:v1:").doesNotContain("my-secret-value");
        assertThat(cipher.decrypt(stored, subscriptionId)).isEqualTo("my-secret-value");
    }

    @Test
    @DisplayName("random IV and data key: encrypting twice yields different ciphertext")
    void nonDeterministic() {
        assertThat(cipher.encrypt("same", subscriptionId)).isNotEqualTo(cipher.encrypt("same", subscriptionId));
    }

    @Test
    @DisplayName("wrong AAD (ciphertext moved to another subscription) fails")
    void wrongAadFails() {
        String stored = cipher.encrypt("s", subscriptionId);
        assertThatThrownBy(() -> cipher.decrypt(stored, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("tampered ciphertext fails authentication")
    void tamperedFails() {
        String stored = cipher.encrypt("s", subscriptionId);
        byte[] raw = java.util.Base64.getDecoder().decode(stored.substring("enc:v1:".length()));
        raw[raw.length - 1] ^= 0x01;
        String tampered = "enc:v1:" + java.util.Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered, subscriptionId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a value without the prefix is refused (the startup backfill is the only way off plaintext)")
    void plaintextIsRefused() {
        assertThat(WebhookSecretCipher.isEncrypted("plain")).isFalse();
        assertThatThrownBy(() -> cipher.decrypt("plain", subscriptionId)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }
}
