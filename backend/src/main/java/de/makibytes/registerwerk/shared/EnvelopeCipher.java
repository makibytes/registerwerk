package de.makibytes.registerwerk.shared;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Envelope encryption for small secrets stored in a database column. Each value gets its own random
 * AES-256 data key (DEK), wrapped by a key-encryption key through {@link KeyWrapper} (an adapter over
 * the wallet module's {@code KekProvider}: KMS/HSM in production, the env-var provider in dev). The
 * value is AES-256-GCM encrypted with a random 12-byte IV and a caller-supplied AAD, so a ciphertext
 * cannot be moved onto another row (webhook subscription, user, ...).
 *
 * <p>Stored form: {@code enc:v1:<base64(u16 wrappedDekLen | wrappedDek | iv | ciphertext+tag)>}.
 * The format is shared by every user of this helper; the AAD is what separates their domains.
 *
 * <p>This class lives in {@code shared} and therefore must not depend on the wallet module (that would
 * be a module cycle); callers adapt their {@code KekProvider} to {@link KeyWrapper}.
 */
public final class EnvelopeCipher {

    public static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    /**
     * Wrap/unwrap contract of the key-encryption key. The wallet module's {@code KekProvider} extends this,
     * so a consumer outside the wallet module (step-up, webhook) can take the platform KEK through this
     * shared type without depending on the wallet module (which would create a module cycle).
     */
    public interface KeyWrapper {
        /** Human-readable name of the key provider; stored next to ciphertexts as a key id. */
        String name();
        byte[] wrap(byte[] plaintextDek);
        byte[] unwrap(byte[] wrappedDek);
    }

    private final KeyWrapper keyWrapper;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeCipher(KeyWrapper keyWrapper) {
        this.keyWrapper = keyWrapper;
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public String encrypt(String plaintext, String aad) {
        byte[] dek = new byte[32];
        random.nextBytes(dek);
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] wrapped = keyWrapper.wrap(dek);
            if (wrapped.length > 0xFFFF) {
                throw new IllegalStateException("Wrapped data key too large");
            }
            ByteBuffer out = ByteBuffer.allocate(2 + wrapped.length + IV_LEN + ct.length);
            out.putShort((short) wrapped.length).put(wrapped).put(iv).put(ct);
            return PREFIX + Base64.getEncoder().encodeToString(out.array());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Envelope encryption failed", e);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * Decrypts a stored value. A value without the {@code enc:} prefix is <em>refused</em>: it is either
     * corruption or a secret still sitting in plaintext, and silently accepting it would keep a plaintext
     * column working forever (H14). Backfilling a column that predates the envelope is the job of a
     * dedicated startup migration that reads the raw value itself, not of the read path.
     *
     * @throws IllegalStateException when {@code stored} is not an envelope value or does not decrypt
     */
    public String decrypt(String stored, String aad) {
        if (!isEncrypted(stored)) {
            throw new IllegalStateException(
                    "Stored value is not envelope-encrypted (no '" + PREFIX + "' prefix); refusing to use it as a secret");
        }
        return openEnvelope(stored, aad);
    }

    /**
     * Like {@link #decrypt} but returns a value without the {@code enc:} prefix unchanged. Only for a column
     * that still has a documented, startup-migrated plaintext backfill window (webhook signing secrets);
     * every new user of this class calls {@link #decrypt}.
     */
    public String decryptAllowingLegacyPlaintext(String stored, String aad) {
        return isEncrypted(stored) ? openEnvelope(stored, aad) : stored;
    }

    private String openEnvelope(String stored, String aad) {
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
            dek = keyWrapper.unwrap(wrapped);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Envelope decryption failed", e);
        } finally {
            if (dek != null) {
                Arrays.fill(dek, (byte) 0);
            }
        }
    }
}
