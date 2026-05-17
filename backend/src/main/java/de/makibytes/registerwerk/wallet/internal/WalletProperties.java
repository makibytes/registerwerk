package de.makibytes.registerwerk.wallet.internal;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for the operator wallet subsystem.
 *
 * <p>The master key ({@code REGISTERWERK_WALLET_MASTER_KEY}) is the only secret needed
 * at runtime. It is used to decrypt keystore files stored at {@link #storageDir}. No
 * private-key material is kept in the database or in application properties.
 *
 * <p>On startup this bean validates that:
 * <ul>
 *   <li>The master key is set and ≥ 32 characters long.</li>
 *   <li>The legacy plaintext env vars ({@code REGISTRY_WALLET_PRIVATE_KEY},
 *       {@code REGISTRY_SOLANA_PRIVATE_KEY}) are NOT set — operators must import
 *       their keys via the operator UI.</li>
 *   <li>The storage directory exists (or can be created).</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "registerwerk.wallet")
public class WalletProperties {

    private static final Logger log = LoggerFactory.getLogger(WalletProperties.class);

    private String masterKey;
    private String storageDir = "/data/wallets";

    /** Detected legacy vars — used only for startup validation. */
    @Value("${registerwerk.wallet.private-key:}")
    private String legacyEvmKey;

    @Value("${registerwerk.wallet.solana-private-key:}")
    private String legacySolanaKey;

    @PostConstruct
    void validate() {
        if (legacyEvmKey != null && !legacyEvmKey.isBlank()) {
            throw new IllegalStateException("""
                    REGISTRY_WALLET_PRIVATE_KEY is still set. This env var has been removed.
                    Please import your key via the Operator Portal → Wallets and remove
                    REGISTRY_WALLET_PRIVATE_KEY from your .env / docker-compose environment.
                    """);
        }
        if (legacySolanaKey != null && !legacySolanaKey.isBlank()) {
            throw new IllegalStateException("""
                    REGISTRY_SOLANA_PRIVATE_KEY is still set. This env var has been removed.
                    Please import your Solana key via the Operator Portal → Wallets and remove
                    REGISTRY_SOLANA_PRIVATE_KEY from your .env / docker-compose environment.
                    """);
        }
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException(
                    "REGISTERWERK_WALLET_MASTER_KEY must be set. Generate a secure value with: " +
                    "openssl rand -base64 32");
        }
        if (masterKey.length() < 32) {
            throw new IllegalStateException(
                    "REGISTERWERK_WALLET_MASTER_KEY must be at least 32 characters long. " +
                    "Generate a secure value with: openssl rand -base64 32");
        }

        Path storagePath = Path.of(storageDir);
        if (!Files.exists(storagePath)) {
            try {
                Files.createDirectories(storagePath);
                log.info("Created wallet storage directory: {}", storagePath.toAbsolutePath());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Cannot create wallet storage directory: " + storagePath, e);
            }
        }

        log.info("Wallet storage: directory={}, master-key=<set, {} chars>",
                storagePath.toAbsolutePath(), masterKey.length());
    }

    public String getMasterKey() { return masterKey; }
    public void setMasterKey(String masterKey) { this.masterKey = masterKey; }

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
}
