package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.shared.EnvelopeCipher;
import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Envelope encryption for webhook signing secrets at rest (5D-09). The mechanics live in the shared
 * {@link EnvelopeCipher} (a random AES-256 data key per secret, wrapped by the platform
 * {@link KekProvider}); this class only binds the subscription id as AAD, so a ciphertext cannot be
 * moved onto another subscription's row.
 *
 * <p>Stored form: {@code enc:v1:<base64(u16 wrappedDekLen | wrappedDek | iv | ciphertext+tag)>}.
 */
@Component
class WebhookSecretCipher {

    static final String PREFIX = EnvelopeCipher.PREFIX;

    private final EnvelopeCipher cipher;

    WebhookSecretCipher(KekProvider kekProvider) {
        this.cipher = new EnvelopeCipher(kekProvider);
    }

    static boolean isEncrypted(String stored) {
        return EnvelopeCipher.isEncrypted(stored);
    }

    String encrypt(String plaintext, UUID subscriptionId) {
        return cipher.encrypt(plaintext, aad(subscriptionId));
    }

    /**
     * Decrypts a stored secret. A value without the {@code enc:} prefix is a legacy plaintext row
     * (written before V28) and is returned as-is until the startup maintenance re-encrypts it.
     */
    String decrypt(String stored, UUID subscriptionId) {
        try {
            return cipher.decryptAllowingLegacyPlaintext(stored, aad(subscriptionId));
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Webhook secret decryption failed", e.getCause());
        }
    }

    private static String aad(UUID subscriptionId) {
        return "webhook-subscription:" + subscriptionId;
    }
}
