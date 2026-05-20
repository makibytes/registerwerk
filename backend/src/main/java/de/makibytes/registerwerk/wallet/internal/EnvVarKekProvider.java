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
        log.warn("Using EnvVarKekProvider — suitable only for dev/test. Set a KMS provider for production.");
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
        try {
            ByteBuffer buf = ByteBuffer.wrap(wrappedDek);
            byte[] iv = new byte[GCM_IV_LEN];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(rawKey, "AES"), new GCMParameterSpec(GCM_TAG_LEN, iv));
            return c.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("KEK unwrap failed", e);
        }
    }
}
