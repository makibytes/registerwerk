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
 * Stored as Web3 Secret Storage v3 keystore JSON files. A random per-wallet DEK is
 * generated and used as the keystore password. The DEK is wrapped via {@link KekProvider}
 * and stored as a {@code .dek.bin} sidecar file alongside the keystore JSON. Legacy
 * keystores (no sidecar) fall back to the master key as password.
 *
 * <h3>Solana/Canton wallets</h3>
 * Stored as a JSON envelope. Version 2 envelopes use a random per-wallet DEK wrapped
 * via {@link KekProvider} for AES-256-GCM encryption. Version 1 (legacy) uses
 * PBKDF2-HMAC-SHA256 from the master key for backward compatibility.
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
    private static final int  PBKDF2_ITER     = 600_000; // OWASP 2023 baseline for PBKDF2-HMAC-SHA256
    private static final int  AES_KEY_BITS    = 256;
    private static final int  DEK_BYTES       = 32;  // 256-bit DEK

    private static final SecureRandom RNG = new SecureRandom();
    private static final ObjectMapper MAPPER = ObjectMapperFactory.getObjectMapper();

    private final WalletProperties props;
    private final KekProvider kekProvider;

    public WalletStorage(WalletProperties props, KekProvider kekProvider) {
        this.props = props;
        this.kekProvider = kekProvider;
        log.info("WalletStorage initialized with KekProvider={}", kekProvider.name());
    }

    // ── EVM (Web3 Secret Storage v3) ──────────────────────────────────────────

    /**
     * Stores an EVM keypair on disk. A random per-wallet DEK is generated, used as the
     * scrypt password, and stored wrapped by {@link KekProvider} in a {@code .dek.bin} sidecar.
     *
     * @return path of the written keystore file (relative to storage root)
     */
    public String storeEvm(UUID walletId, ECKeyPair ecKeyPair) {
        try {
            byte[] dek = randomBytes(DEK_BYTES);
            byte[] wrappedDek = kekProvider.wrap(dek);
            String dekPassword = HexFormat.of().formatHex(dek);

            WalletFile wf = Wallet.createStandard(dekPassword, ecKeyPair); // full scrypt N=2^18
            String json = MAPPER.writeValueAsString(wf);
            Path keystorePath = storagePath(walletId + ".json");
            Files.writeString(keystorePath, json, StandardCharsets.UTF_8);
            Files.write(storagePath(walletId + ".dek.bin"), wrappedDek);
            log.debug("Stored EVM keystore with wrapped DEK: {}", keystorePath);
            return walletId + ".json";
        } catch (Exception e) {
            throw new WalletStorageException("Failed to store EVM keystore for wallet " + walletId, e);
        }
    }

    /**
     * Loads and decrypts an EVM keystore. Uses the wrapped DEK sidecar when present;
     * falls back to the master key for keystores created before envelope encryption.
     */
    public Credentials loadEvm(String relativePath) {
        try {
            Path path = storagePath(relativePath);
            String json = Files.readString(path, StandardCharsets.UTF_8);
            WalletFile wf = MAPPER.readValue(json, WalletFile.class);

            Path dekPath = storagePath(relativePath.replace(".json", ".dek.bin"));
            String password;
            if (Files.exists(dekPath)) {
                byte[] wrappedDek = Files.readAllBytes(dekPath);
                byte[] dek = kekProvider.unwrap(wrappedDek);
                password = HexFormat.of().formatHex(dek);
            } else {
                password = props.getMasterKey(); // legacy keystores
            }

            ECKeyPair pair = Wallet.decrypt(password, wf);
            return Credentials.create(pair);
        } catch (CipherException e) {
            throw new WalletStorageException("Failed to decrypt EVM keystore at " + relativePath +
                    ": wrong key or corrupted file", e);
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
     * Stores a Solana keypair (64-byte ed25519 secret key) encrypted with AES-256-GCM.
     * Uses a random per-wallet DEK wrapped by {@link KekProvider} (version 2 envelope).
     *
     * @return relative path of the stored file
     */
    public String storeSolana(UUID walletId, byte[] secretKeyBytes) {
        try {
            byte[] dek  = randomBytes(DEK_BYTES);
            byte[] wrappedDek = kekProvider.wrap(dek);
            byte[] iv   = randomBytes(GCM_IV_LENGTH);
            SecretKey aesKey = new SecretKeySpec(dek, "AES");

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(secretKeyBytes);

            HexFormat hex = HexFormat.of();
            Map<String, String> envelope = Map.of(
                    "version",    "2",
                    "type",       "solana",
                    "wrappedDek", hex.formatHex(wrappedDek),
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
     * Version 2 envelopes use KekProvider; version 1 falls back to PBKDF2 from master key.
     */
    public byte[] loadSolana(String relativePath) {
        try {
            Path path = storagePath(relativePath);
            @SuppressWarnings("unchecked")
            Map<String, String> envelope = MAPPER.readValue(
                    Files.readString(path, StandardCharsets.UTF_8), Map.class);

            HexFormat hex = HexFormat.of();
            byte[] iv         = hex.parseHex(envelope.get("iv"));
            byte[] ciphertext = hex.parseHex(envelope.get("ciphertext"));

            SecretKey aesKey = resolveAesKey(envelope, hex);
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
     * Uses the same version 2 envelope format as Solana keystores.
     *
     * @param walletId the wallet's UUID (used as filename base)
     * @param partyId  Canton party ID string
     * @param jwt      bearer JWT granting submission rights for this party
     * @return relative path of the stored file
     */
    public String storeCanton(UUID walletId, String partyId, String jwt) {
        try {
            byte[] payload = (partyId + "\n" + jwt).getBytes(StandardCharsets.UTF_8);

            byte[] dek = randomBytes(DEK_BYTES);
            byte[] wrappedDek = kekProvider.wrap(dek);
            byte[] iv  = randomBytes(GCM_IV_LENGTH);
            SecretKey aesKey = new SecretKeySpec(dek, "AES");

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(payload);

            HexFormat hex = HexFormat.of();
            Map<String, String> envelope = Map.of(
                    "version",    "2",
                    "type",       "canton",
                    "wrappedDek", hex.formatHex(wrappedDek),
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
     * Version 2 envelopes use KekProvider; version 1 falls back to PBKDF2 from master key.
     */
    public CantonContext loadCanton(String relativePath) {
        try {
            Path path = storagePath(relativePath);
            @SuppressWarnings("unchecked")
            Map<String, String> envelope = MAPPER.readValue(
                    Files.readString(path, StandardCharsets.UTF_8), Map.class);

            HexFormat hex = HexFormat.of();
            byte[] iv         = hex.parseHex(envelope.get("iv"));
            byte[] ciphertext = hex.parseHex(envelope.get("ciphertext"));

            SecretKey aesKey = resolveAesKey(envelope, hex);
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

    /**
     * Resolves an AES key from the envelope map. Version 2 unwraps via KekProvider;
     * version 1 derives via PBKDF2 from the master key (backward compatibility).
     */
    private SecretKey resolveAesKey(Map<String, String> envelope, HexFormat hex) throws Exception {
        if ("2".equals(envelope.get("version"))) {
            byte[] wrappedDek = hex.parseHex(envelope.get("wrappedDek"));
            byte[] dek = kekProvider.unwrap(wrappedDek);
            return new SecretKeySpec(dek, "AES");
        }
        byte[] salt = hex.parseHex(envelope.get("salt"));
        return deriveAesKey(props.getMasterKey(), salt);
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
