package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * Envelope encryption for webhook signing secrets at rest (5D-09). Each secret gets its own random
 * AES-256 data key, wrapped by the platform {@link KekProvider} (the wallet module's KEK contract:
 * KMS/HSM in production, the env-var provider in dev). The secret itself is AES-256-GCM encrypted
 * with a random 12-byte IV and the subscription id as AAD, so a ciphertext cannot be moved onto
 * another subscription's row.
 *
 * <p>Stored form: {@code enc:v1:<base64(u16 wrappedDekLen | wrappedDek | iv | ciphertext+tag)>}.
 */
@Component
class WebhookSecretCipher {

    static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final KekProvider kekProvider;
    private final SecureRandom random = new SecureRandom();

    WebhookSecretCipher(KekProvider kekProvider) {
        this.kekProvider = kekProvider;
    }

    static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    String encrypt(String plaintext, UUID subscriptionId) {
        byte[] dek = new byte[32];
        random.nextBytes(dek);
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(subscriptionId));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] wrapped = kekProvider.wrap(dek);
            if (wrapped.length > 0xFFFF) {
                throw new IllegalStateException("Wrapped data key too large");
            }
            ByteBuffer out = ByteBuffer.allocate(2 + wrapped.length + IV_LEN + ct.length);
            out.putShort((short) wrapped.length).put(wrapped).put(iv).put(ct);
            return PREFIX + Base64.getEncoder().encodeToString(out.array());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Webhook secret encryption failed", e);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * Decrypts a stored secret. A value without the {@code enc:} prefix is a legacy plaintext row
     * (written before V28) and is returned as-is until the startup maintenance re-encrypts it.
     */
    String decrypt(String stored, UUID subscriptionId) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        byte[] dek = null;
        try {
            ByteBuffer in = ByteBuffer.wrap(Base64.getDecoder().decode(stored.substring(PREFIX.length())));
            int wrappedLen = in.getShort() & 0xFFFF;
            byte[] wrapped = new byte[wrappedLen];
            in.get(wrapped);
            byte[] iv = new byte[IV_LEN];
            in.get(iv);
            byte[] ct = new byte[in.remaining()];
            in.get(ct);
            dek = kekProvider.unwrap(wrapped);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(subscriptionId));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Webhook secret decryption failed", e);
        } finally {
            if (dek != null) {
                Arrays.fill(dek, (byte) 0);
            }
        }
    }

    private static byte[] aad(UUID subscriptionId) {
        return ("webhook-subscription:" + subscriptionId).getBytes(StandardCharsets.UTF_8);
    }
}
