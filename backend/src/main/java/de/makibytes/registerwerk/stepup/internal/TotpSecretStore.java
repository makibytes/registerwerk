package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.shared.EnvelopeCipher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Encrypts TOTP secrets at rest with the platform KEK (K3, 6-09), using the same envelope scheme as
 * webhook secrets. The user id is the AAD, so a ciphertext cannot be copied to another user's row.
 */
@Component
class TotpSecretStore {

    private final EnvelopeCipher cipher;
    private final EnvelopeCipher.KeyWrapper kek;

    /** @param kek the platform KEK (the wallet module's {@code KekProvider}, seen through the shared contract) */
    TotpSecretStore(EnvelopeCipher.KeyWrapper kek) {
        this.kek = kek;
        this.cipher = new EnvelopeCipher(kek);
    }

    String encrypt(UUID userId, String base32Secret) {
        return cipher.encrypt(base32Secret, aad(userId));
    }

    /**
     * Decrypts. A value without the {@code enc:} prefix is refused (H14): plaintext secrets are moved to
     * ciphertext only by {@link TotpSecretMigration} at startup, never accepted on the verification path.
     */
    String decrypt(UUID userId, String stored) {
        try {
            return cipher.decrypt(stored, aad(userId));
        } catch (IllegalStateException e) {
            throw new IllegalStateException("TOTP secret decryption failed", e.getCause());
        }
    }

    boolean isEncrypted(String stored) {
        return EnvelopeCipher.isEncrypted(stored);
    }

    String kid() {
        return kek.name();
    }

    private static String aad(UUID userId) {
        return "totp-secret:" + userId;
    }
}
