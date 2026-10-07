package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Fallback KEK provider backed by an environment-variable master key.
 * DEV AND TEST ONLY — rejected at startup when REGISTERWERK_PRODUCTION_MODE=true.
 * Replace with AwsKmsKekProvider / AzureKeyVaultKekProvider / Pkcs11HsmKekProvider in prod.
 */
@Component
@ConditionalOnMissingBean(name = {"awsKmsKekProvider", "azureKeyVaultKekProvider", "gcpKmsKekProvider", "pkcs11HsmKekProvider"})
class EnvVarKekProvider implements KekProvider {

    private static final Logger log = LoggerFactory.getLogger(EnvVarKekProvider.class);
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_LEN = 128;

    private final byte[] rawKey;
    private final String activeVersion;
    /** Retired master keys that may still unwrap old data keys, by version label (insertion order). */
    private final java.util.Map<String, byte[]> previousKeys = new java.util.concurrent.ConcurrentHashMap<>();

    EnvVarKekProvider(WalletProperties props) {
        String masterKey = props.getMasterKey();
        if (masterKey == null || masterKey.isBlank()) {
            log.warn("REGISTERWERK_WALLET_MASTER_KEY is not set; wallet encryption is unavailable.");
            this.rawKey = new byte[32];
        } else {
            try {
                this.rawKey = Arrays.copyOf(
                        MessageDigest.getInstance("SHA-256")
                                .digest(masterKey.getBytes(StandardCharsets.UTF_8)), 32);
            } catch (Exception e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
        this.activeVersion = props.getMasterKeyVersion();
        props.getPreviousMasterKeys().forEach((label, key) -> previousKeys.put(label, derive(key)));
        log.warn("Using EnvVarKekProvider — suitable only for dev/test. Set a KMS provider for production.");
    }

    private static byte[] derive(String masterKey) {
        try {
            return Arrays.copyOf(MessageDigest.getInstance("SHA-256")
                    .digest(masterKey.getBytes(StandardCharsets.UTF_8)), 32);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public java.util.Optional<String> activeVersion() {
        return java.util.Optional.of(activeVersion);
    }

    @Override
    public java.util.Set<String> configuredVersions() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        all.add(activeVersion);
        all.addAll(new java.util.TreeSet<>(previousKeys.keySet()));
        return all;
    }

    @Override
    public java.util.Optional<String> versionOf(byte[] wrappedDek) {
        if (open(rawKey, wrappedDek) != null) {
            return java.util.Optional.of(activeVersion);
        }
        return previousKeys.entrySet().stream().filter(e -> open(e.getValue(), wrappedDek) != null)
                .map(java.util.Map.Entry::getKey).findFirst();
    }

    @Override
    public boolean disableVersion(String version) {
        return !version.equals(activeVersion) && previousKeys.remove(version) != null;
    }

    @Override
    public String name() {
        return "ENV_VAR_KEK";
    }

    @Override
    public byte[] wrap(byte[] plaintextDek) {
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(rawKey, "AES"), new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] ciphertext = c.doFinal(plaintextDek);
            return ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
        } catch (Exception e) {
            throw new IllegalStateException("KEK wrap failed", e);
        }
    }

    @Override
    public byte[] unwrap(byte[] wrappedDek) {
        byte[] dek = open(rawKey, wrappedDek);
        for (byte[] key : previousKeys.values()) {
            if (dek != null) {
                break;
            }
            dek = open(key, wrappedDek);
        }
        if (dek == null) {
            throw new IllegalStateException("KEK unwrap failed");
        }
        return dek;
    }

    /** The data key, or null when {@code key} did not wrap this value. */
    private static byte[] open(byte[] key, byte[] wrappedDek) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(wrappedDek);
            byte[] iv = new byte[GCM_IV_LEN];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LEN, iv));
            return c.doFinal(ciphertext);
        } catch (Exception e) {
            return null;
        }
    }
}
