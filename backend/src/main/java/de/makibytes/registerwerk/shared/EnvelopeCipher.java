package de.makibytes.registerwerk.shared;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

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

        /**
         * Label of the key version that {@link #wrap} uses now, or empty when the provider does not expose
         * versions (then nothing is ever "on an old version" and no re-wrap is attempted).
         */
        default Optional<String> activeVersion() {
            return Optional.empty();
        }

        /** Label of the key version that wrapped {@code wrappedDek}; empty when it cannot be attributed. */
        default Optional<String> versionOf(byte[] wrappedDek) {
            return Optional.empty();
        }

        /** Re-wraps a DEK under the active version. Default: unwrap then wrap; KMS providers may do it server side. */
        default byte[] rewrap(byte[] wrappedDek) {
            return wrap(unwrap(wrappedDek));
        }
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

    /** Label of the KEK version that wrapped this value's data key, {@code "unknown"} when it cannot be told. */
    public String versionLabel(String stored) {
        if (keyWrapper.activeVersion().isEmpty()) {
            return "unversioned";
        }
        try {
            return keyWrapper.versionOf(parse(stored).wrapped()).orElse("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /** True when the provider exposes versions and this value's data key is not on the active one. */
    public boolean needsRewrap(String stored) {
        Optional<String> active = keyWrapper.activeVersion();
        return active.isPresent() && !active.get().equals(versionLabel(stored));
    }

    /**
     * Re-wraps only the data key under the active KEK version; IV, ciphertext and AAD binding are untouched, so
     * the secret itself is never decrypted or exposed. The new wrapping is verified (it must unwrap to the same
     * data key) before the value is returned.
     *
     * @throws IllegalStateException when the old wrapping cannot be opened or the new one does not verify
     */
    public String rewrap(String stored) {
        byte[] oldDek = null;
        byte[] newDek = null;
        try {
            Parsed p = parse(stored);
            oldDek = keyWrapper.unwrap(p.wrapped());
            byte[] rewrapped = keyWrapper.rewrap(p.wrapped());
            newDek = keyWrapper.unwrap(rewrapped);
            if (!java.security.MessageDigest.isEqual(oldDek, newDek) || rewrapped.length > 0xFFFF) {
                throw new IllegalStateException("Re-wrapped data key does not verify");
            }
            ByteBuffer out = ByteBuffer.allocate(2 + rewrapped.length + p.rest().length);
            out.putShort((short) rewrapped.length).put(rewrapped).put(p.rest());
            return PREFIX + Base64.getEncoder().encodeToString(out.array());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Envelope re-wrap failed", e);
        } finally {
            if (oldDek != null) {
                Arrays.fill(oldDek, (byte) 0);
            }
            if (newDek != null) {
                Arrays.fill(newDek, (byte) 0);
            }
        }
    }

    private record Parsed(byte[] wrapped, byte[] rest) { }

    private static Parsed parse(String stored) {
        ByteBuffer in = ByteBuffer.wrap(Base64.getDecoder().decode(stored.substring(PREFIX.length())));
        byte[] wrapped = new byte[in.getShort() & 0xFFFF];
        in.get(wrapped);
        byte[] rest = new byte[in.remaining()];
        in.get(rest);
        return new Parsed(wrapped, rest);
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
