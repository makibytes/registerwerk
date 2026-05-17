package de.makibytes.registerwerk.wallet.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.makibytes.registerwerk.wallet.internal.WalletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.crypto.exception.CipherException;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Wallet;
import org.web3j.crypto.WalletFile;
import org.web3j.protocol.ObjectMapperFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Low-level encrypted keystore I/O for operator wallets.
 *
 * <h3>EVM wallets</h3>
 * Stored as Web3 Secret Storage v3 keystore JSON files, encrypted with the master KEK
 * via scrypt + AES-128-CTR (web3j's {@code Wallet.createLight}). The master KEK acts
 * as the keystore password — it never needs to be entered by an operator at runtime.
 *
 * <h3>Solana wallets</h3>
 * Stored as a JSON envelope {@code {salt, iv, ciphertext}} where the 64-byte ed25519
 * keypair is encrypted with AES-256-GCM. The 32-byte AES key is derived with
 * PBKDF2-HMAC-SHA256 (100 000 iterations) from the master KEK + a per-wallet random salt.
 *
 * <h3>Export (user-facing)</h3>
 * EVM: re-encrypt with the user-chosen password and full scrypt before returning JSON.
 * Solana: not currently exported as a standard keystore; raw bytes returned only.
 */
@Component
public class WalletStorage {

    private static final Logger log = LoggerFactory.getLogger(WalletStorage.class);

    private static final int  GCM_IV_LENGTH   = 12;  // bytes
    private static final int  GCM_TAG_LENGTH  = 128; // bits
    private static final int  SALT_LENGTH     = 16;  // bytes
    private static final int  PBKDF2_ITER     = 100_000;
    private static final int  AES_KEY_BITS    = 256;

    private static final SecureRandom RNG = new SecureRandom();
    private static final ObjectMapper MAPPER = ObjectMapperFactory.getObjectMapper();

    private final WalletProperties props;

    public WalletStorage(WalletProperties props) {
        this.props = props;
    }

    // ── EVM (Web3 Secret Storage v3) ──────────────────────────────────────────

    /**
     * Stores an EVM keypair on disk encrypted with the master KEK.
     *
     * @return path of the written keystore file (relative to storage root)
     */
    public String storeEvm(UUID walletId, ECKeyPair ecKeyPair) {
        try {
            WalletFile wf = Wallet.createLight(props.getMasterKey(), ecKeyPair);
            String json = MAPPER.writeValueAsString(wf);
            Path path = storagePath(walletId + ".json");
            Files.writeString(path, json, StandardCharsets.UTF_8);
            log.debug("Stored EVM keystore: {}", path);
            return walletId + ".json";
        } catch (Exception e) {
            throw new WalletStorageException("Failed to store EVM keystore for wallet " + walletId, e);
        }
    }

    /**
     * Loads and decrypts an EVM keystore from the given relative path.
     */
    public Credentials loadEvm(String relativePath) {
        try {
            Path path = storagePath(relativePath);
            String json = Files.readString(path, StandardCharsets.UTF_8);
            WalletFile wf = MAPPER.readValue(json, WalletFile.class);
            ECKeyPair pair = Wallet.decrypt(props.getMasterKey(), wf);
            return Credentials.create(pair);
        } catch (CipherException e) {
            throw new WalletStorageException("Failed to decrypt EVM keystore at " + relativePath +
                    ": master key mismatch or corrupted file", e);
        } catch (Exception e) {
            throw new WalletStorageException("Failed to load EVM keystore at " + relativePath, e);
        }
    }

    /**
     * Imports an EVM keystore supplied by the operator (potentially with their own password)
     * and re-encrypts it under the master KEK for at-rest storage.
     *
     * @param walletId     ID to assign to the new wallet file
     * @param keystoreJson raw keystore JSON from the operator
     * @param userPassword the operator's password for the supplied keystore
     * @return relative path of the stored file
     */
    public String importEvmKeystore(UUID walletId, String keystoreJson, String userPassword) {
        try {
            WalletFile userWf = MAPPER.readValue(keystoreJson, WalletFile.class);
            ECKeyPair pair = Wallet.decrypt(userPassword, userWf);
            return storeEvm(walletId, pair);
        } catch (CipherException e) {
            throw new WalletStorageException("Wrong password for the supplied EVM keystore", e);
        } catch (WalletStorageException e) {
            throw e;
        } catch (Exception e) {
            throw new WalletStorageException("Failed to import EVM keystore", e);
        }
    }

    /**
     * Imports a raw EVM private key (hex string) and stores it encrypted under the master KEK.
     *
     * @param walletId     ID to assign
     * @param privateKeyHex 64-hex-char private key (with or without 0x prefix)
     * @return relative path of the stored file
     */
    public String importEvmRaw(UUID walletId, String privateKeyHex) {
        String hex = privateKeyHex.startsWith("0x") ? privateKeyHex.substring(2) : privateKeyHex;
        ECKeyPair pair = ECKeyPair.create(new java.math.BigInteger(hex, 16));
        return storeEvm(walletId, pair);
    }

    /**
     * Exports an EVM wallet as a Web3 Secret Storage v3 keystore encrypted with the
     * operator-chosen {@code exportPassword}. Uses full scrypt for stronger protection.
     *
     * @return keystore JSON string suitable for file download
     */
    public String exportEvmKeystore(String relativePath, String exportPassword) {
        try {
            Credentials creds = loadEvm(relativePath);
            WalletFile exported = Wallet.createStandard(exportPassword, creds.getEcKeyPair());
            return MAPPER.writeValueAsString(exported);
        } catch (Exception e) {
            throw new WalletStorageException("Failed to export EVM keystore from " + relativePath, e);
        }
    }

    /**
     * Returns the raw EVM private key hex string (0x-prefixed).
     * Caller is responsible for audit logging and access control.
     */
    public String exportEvmRaw(String relativePath) {
        Credentials creds = loadEvm(relativePath);
        return "0x" + creds.getEcKeyPair().getPrivateKey().toString(16);
    }

    // ── Solana (AES-256-GCM envelope) ────────────────────────────────────────

    /**
     * Stores a Solana keypair (64-byte ed25519 secret key) encrypted with AES-256-GCM
     * using a PBKDF2-derived key from the master KEK + a random per-wallet salt.
     *
     * @return relative path of the stored file
     */
    public String storeSolana(UUID walletId, byte[] secretKeyBytes) {
        try {
            byte[] salt = randomBytes(SALT_LENGTH);
            byte[] iv   = randomBytes(GCM_IV_LENGTH);
            SecretKey aesKey = deriveAesKey(props.getMasterKey(), salt);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(secretKeyBytes);

            HexFormat hex = HexFormat.of();
            Map<String, String> envelope = Map.of(
                    "version",    "1",
                    "type",       "solana",
                    "salt",       hex.formatHex(salt),
                    "iv",         hex.formatHex(iv),
                    "ciphertext", hex.formatHex(ciphertext)
            );

            Path path = storagePath(walletId + ".json");
            Files.writeString(path, MAPPER.writeValueAsString(envelope), StandardCharsets.UTF_8);
            log.debug("Stored Solana keystore: {}", path);
            return walletId + ".json";
        } catch (Exception e) {
            throw new WalletStorageException("Failed to store Solana keystore for wallet " + walletId, e);
        }
    }

    /**
     * Decrypts and returns the raw Solana secret key bytes (64 bytes).
     */
    public byte[] loadSolana(String relativePath) {
        try {
            Path path = storagePath(relativePath);
            @SuppressWarnings("unchecked")
            Map<String, String> envelope = MAPPER.readValue(
                    Files.readString(path, StandardCharsets.UTF_8), Map.class);

            HexFormat hex = HexFormat.of();
            byte[] salt       = hex.parseHex(envelope.get("salt"));
            byte[] iv         = hex.parseHex(envelope.get("iv"));
            byte[] ciphertext = hex.parseHex(envelope.get("ciphertext"));

            SecretKey aesKey = deriveAesKey(props.getMasterKey(), salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new WalletStorageException("Failed to load Solana keystore at " + relativePath, e);
        }
    }

    /**
     * Imports a raw Solana private key (hex-encoded 64-byte keypair or 32-byte seed).
     *
     * @return relative path of the stored file
     */
    public String importSolanaRaw(UUID walletId, String privateKeyHex) {
        String hex = privateKeyHex.startsWith("0x") ? privateKeyHex.substring(2) : privateKeyHex;
        byte[] keyBytes = HexFormat.of().parseHex(hex);
        return storeSolana(walletId, keyBytes);
    }

    // ── Canton (AES-256-GCM envelope, same scheme as Solana) ─────────────────

    /**
     * Stores a Canton party JWT (and optional party ID) encrypted with AES-256-GCM.
     * Uses the same envelope format as Solana keystores for consistency.
     *
     * @param walletId the wallet's UUID (used as filename base)
     * @param partyId  Canton party ID string (stored in plaintext inside the envelope)
     * @param jwt      bearer JWT granting submission rights for this party
     * @return relative path of the stored file
     */
    public String storeCanton(UUID walletId, String partyId, String jwt) {
        try {
            byte[] payload = (partyId + "\n" + jwt).getBytes(StandardCharsets.UTF_8);

            byte[] salt = randomBytes(SALT_LENGTH);
            byte[] iv   = randomBytes(GCM_IV_LENGTH);
            SecretKey aesKey = deriveAesKey(props.getMasterKey(), salt);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(payload);

            HexFormat hex = HexFormat.of();
            Map<String, String> envelope = Map.of(
                    "version",    "1",
                    "type",       "canton",
                    "salt",       hex.formatHex(salt),
                    "iv",         hex.formatHex(iv),
                    "ciphertext", hex.formatHex(ciphertext)
            );

            Path path = storagePath(walletId + ".json");
            Files.writeString(path, MAPPER.writeValueAsString(envelope), StandardCharsets.UTF_8);
            log.debug("Stored Canton keystore: {}", path);
            return walletId + ".json";
        } catch (Exception e) {
            throw new WalletStorageException("Failed to store Canton keystore for wallet " + walletId, e);
        }
    }

    /**
     * Decrypts and returns a {@link CantonContext} containing the party ID and JWT.
     */
    public CantonContext loadCanton(String relativePath) {
        try {
            Path path = storagePath(relativePath);
            @SuppressWarnings("unchecked")
            Map<String, String> envelope = MAPPER.readValue(
                    Files.readString(path, StandardCharsets.UTF_8), Map.class);

            HexFormat hex = HexFormat.of();
            byte[] salt       = hex.parseHex(envelope.get("salt"));
            byte[] iv         = hex.parseHex(envelope.get("iv"));
            byte[] ciphertext = hex.parseHex(envelope.get("ciphertext"));

            SecretKey aesKey = deriveAesKey(props.getMasterKey(), salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            String[] parts = new String(plaintext, StandardCharsets.UTF_8).split("\n", 2);
            return new CantonContext(parts[0].strip(), parts.length > 1 ? parts[1].strip() : "");
        } catch (Exception e) {
            throw new WalletStorageException("Failed to load Canton keystore at " + relativePath, e);
        }
    }

    /** Holder for Canton authentication context. */
    public record CantonContext(String partyId, String jwt) {}

    // ── Delete ────────────────────────────────────────────────────────────────

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(storagePath(relativePath));
        } catch (IOException e) {
            log.warn("Failed to delete keystore file {}: {}", relativePath, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path storagePath(String relativePath) {
        return Path.of(props.getStorageDir()).resolve(relativePath);
    }

    private static SecretKey deriveAesKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITER, AES_KEY_BITS);
        byte[] derived = skf.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(derived, "AES");
    }

    private static byte[] randomBytes(int length) {
        byte[] b = new byte[length];
        RNG.nextBytes(b);
        return b;
    }

    public static class WalletStorageException extends RuntimeException {
        public WalletStorageException(String msg) { super(msg); }
        public WalletStorageException(String msg, Throwable cause) { super(msg, cause); }
    }
}
