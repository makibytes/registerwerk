package de.makibytes.registerwerk.application.wallet;

import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.wallet.OperatorWallet;
import de.makibytes.registerwerk.domain.wallet.OperatorWallet.WalletType;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.OperatorWalletRepository;
import de.makibytes.registerwerk.infrastructure.wallet.WalletStorage;
import org.p2p.solanaj.core.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for operator wallet lifecycle: generate, import, export, rename, delete.
 *
 * <p>All operations that touch key material go through {@link WalletStorage}. No private-key
 * bytes pass through this class — only references (wallet IDs, file paths).
 */
@Service
@Transactional
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final OperatorWalletRepository walletRepository;
    private final WalletStorage            walletStorage;
    private final WalletDefaultService     defaultService;
    private final WalletSigner             walletSigner;
    private final AuditEventPublisher      auditEventPublisher;

    public WalletService(
            OperatorWalletRepository walletRepository,
            WalletStorage walletStorage,
            WalletDefaultService defaultService,
            WalletSigner walletSigner,
            AuditEventPublisher auditEventPublisher) {
        this.walletRepository   = walletRepository;
        this.walletStorage      = walletStorage;
        this.defaultService     = defaultService;
        this.walletSigner       = walletSigner;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Transactional(readOnly = true)
    public List<OperatorWallet> listAll() {
        return walletRepository.findAll();
    }

    @Transactional(readOnly = true)
    public OperatorWallet getById(UUID id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("OperatorWallet", id));
    }

    // ── Generate ──────────────────────────────────────────────────────────────

    /**
     * Generates a fresh secp256k1 or ed25519 keypair, stores it encrypted, and returns
     * the new wallet metadata.
     *
     * @param name wallet display name (must be unique)
     * @param type EVM or SOLANA
     */
    public OperatorWallet generate(String name, WalletType type) {
        requireUniqueName(name);
        UUID id = UUID.randomUUID();
        String address;
        String relativePath;

        if (type == WalletType.EVM) {
            ECKeyPair pair = generateEcKeyPair();
            address = Keys.toChecksumAddress(Keys.getAddress(pair));
            relativePath = walletStorage.storeEvm(id, pair);
        } else {
            Account account = new Account();
            address = account.getPublicKey().toBase58();
            relativePath = walletStorage.storeSolana(id, account.getSecretKey());
        }

        OperatorWallet wallet = persist(id, name, type, address, relativePath);
        defaultService.autoPromoteIfFirstOfType(wallet);

        auditEventPublisher.publish(
                "WALLET_GENERATED", "OperatorWallet", wallet.getId(), null, null,
                Map.of("name", name, "type", type.name(), "address", address));
        log.info("Generated {} wallet '{}': address={}", type, name, address);
        return wallet;
    }

    // ── Import raw ────────────────────────────────────────────────────────────

    public OperatorWallet importRaw(String name, WalletType type, String privateKeyHex) {
        requireUniqueName(name);
        UUID id = UUID.randomUUID();
        String address;
        String relativePath;

        if (type == WalletType.EVM) {
            String hex = privateKeyHex.startsWith("0x") ? privateKeyHex.substring(2) : privateKeyHex;
            ECKeyPair pair = ECKeyPair.create(new java.math.BigInteger(hex, 16));
            address = Keys.toChecksumAddress(Keys.getAddress(pair));
            relativePath = walletStorage.importEvmRaw(id, privateKeyHex);
        } else {
            String hex = privateKeyHex.startsWith("0x") ? privateKeyHex.substring(2) : privateKeyHex;
            byte[] keyBytes = java.util.HexFormat.of().parseHex(hex);
            Account account = new Account(keyBytes);
            address = account.getPublicKey().toBase58();
            relativePath = walletStorage.storeSolana(id, keyBytes);
        }

        OperatorWallet wallet = persist(id, name, type, address, relativePath);
        defaultService.autoPromoteIfFirstOfType(wallet);

        auditEventPublisher.publish(
                "WALLET_IMPORTED_RAW", "OperatorWallet", wallet.getId(), null, null,
                Map.of("name", name, "type", type.name(), "address", address));
        log.info("Imported raw {} key as wallet '{}': address={}", type, name, address);
        return wallet;
    }

    // ── Import keystore ───────────────────────────────────────────────────────

    /**
     * Imports a Web3 Secret Storage v3 keystore JSON (EVM only) supplied by the operator.
     * The file is decrypted with {@code userPassword}, then re-encrypted under the master KEK.
     */
    public OperatorWallet importKeystore(String name, String keystoreJson, String userPassword) {
        requireUniqueName(name);
        UUID id = UUID.randomUUID();
        String relativePath = walletStorage.importEvmKeystore(id, keystoreJson, userPassword);

        Credentials creds = walletStorage.loadEvm(relativePath);
        String address = creds.getAddress();

        OperatorWallet wallet = persist(id, name, WalletType.EVM, address, relativePath);
        defaultService.autoPromoteIfFirstOfType(wallet);

        auditEventPublisher.publish(
                "WALLET_IMPORTED_KEYSTORE", "OperatorWallet", wallet.getId(), null, null,
                Map.of("name", name, "address", address));
        log.info("Imported keystore as wallet '{}': address={}", name, address);
        return wallet;
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Exports the wallet as a Web3 Secret Storage v3 JSON encrypted with {@code exportPassword}.
     * Returns the JSON string; controller writes it as a file download.
     */
    public String exportKeystore(UUID walletId, String exportPassword) {
        OperatorWallet wallet = getById(walletId);
        if (wallet.getType() != WalletType.EVM) {
            throw new UnsupportedOperationException("Keystore export is only supported for EVM wallets");
        }
        String json = walletStorage.exportEvmKeystore(wallet.getKeystorePath(), exportPassword);
        auditEventPublisher.publish(
                "WALLET_EXPORTED_KEYSTORE", "OperatorWallet", walletId, null, null,
                Map.of("name", wallet.getName(), "address", wallet.getAddress()));
        log.info("Exported keystore for wallet '{}'", wallet.getName());
        return json;
    }

    /**
     * Returns the raw private key hex. This is a dangerous operation; caller must
     * gate it behind an explicit confirmation and the result is audit-logged.
     */
    public String exportRaw(UUID walletId) {
        OperatorWallet wallet = getById(walletId);
        String raw = wallet.getType() == WalletType.EVM
                ? walletStorage.exportEvmRaw(wallet.getKeystorePath())
                : java.util.HexFormat.of().formatHex(walletStorage.loadSolana(wallet.getKeystorePath()));

        auditEventPublisher.publish(
                "WALLET_EXPORTED_RAW", "OperatorWallet", walletId, null, null,
                Map.of("name", wallet.getName(), "address", wallet.getAddress()));
        log.warn("RAW private key exported for wallet '{}' ({})", wallet.getName(), walletId);
        return raw;
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    public OperatorWallet rename(UUID walletId, String newName) {
        OperatorWallet wallet = getById(walletId);
        String oldName = wallet.getName();
        requireUniqueName(newName);
        wallet.setName(newName);
        OperatorWallet saved = walletRepository.save(wallet);
        auditEventPublisher.publish(
                "WALLET_RENAMED", "OperatorWallet", walletId, null, null,
                Map.of("oldName", oldName, "newName", newName));
        return saved;
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Deletes a wallet. Removes all chain defaults pointing to it first (the operator
     * is responsible for setting a replacement default before any chain operation runs).
     */
    public void delete(UUID walletId) {
        OperatorWallet wallet = getById(walletId);
        defaultService.removeDefaultsForWallet(walletId);
        walletSigner.evict(walletId);
        walletStorage.delete(wallet.getKeystorePath());
        walletRepository.delete(wallet);
        auditEventPublisher.publish(
                "WALLET_DELETED", "OperatorWallet", walletId, null, null,
                Map.of("name", wallet.getName(), "address", wallet.getAddress()));
        log.info("Deleted wallet '{}' ({})", wallet.getName(), walletId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void requireUniqueName(String name) {
        walletRepository.findByName(name).ifPresent(w -> {
            throw new IllegalArgumentException("A wallet named '" + name + "' already exists");
        });
    }

    private OperatorWallet persist(UUID id, String name, WalletType type,
                                   String address, String keystorePath) {
        OperatorWallet w = new OperatorWallet();
        w.setName(name);
        w.setType(type);
        w.setAddress(address);
        w.setKeystorePath(keystorePath);
        return walletRepository.save(w);
    }

    private static ECKeyPair generateEcKeyPair() {
        try {
            return Keys.createEcKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate EVM keypair", e);
        }
    }
}
