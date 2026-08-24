package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.wallet.events.WalletGeneratedEvent;
import de.makibytes.registerwerk.wallet.events.WalletImportedRawEvent;
import de.makibytes.registerwerk.wallet.events.WalletImportedKeystoreEvent;
import de.makibytes.registerwerk.wallet.events.WalletExportedKeystoreEvent;
import de.makibytes.registerwerk.wallet.events.WalletExportedRawEvent;
import de.makibytes.registerwerk.wallet.events.WalletRenamedEvent;
import de.makibytes.registerwerk.wallet.events.WalletDeletedEvent;
import de.makibytes.registerwerk.wallet.events.WalletKekRotatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.wallet.api.OperatorWallet;
import de.makibytes.registerwerk.wallet.api.OperatorWallet.WalletType;
import de.makibytes.registerwerk.wallet.api.OperatorWalletRepository;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.wallet.api.WalletStorage;
import de.makibytes.registerwerk.wallet.api.WalletManagement;
import org.p2p.solanaj.core.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Credentials;
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
public class WalletService implements WalletManagement {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final OperatorWalletRepository walletRepository;
    private final WalletStorage            walletStorage;
    private final WalletDefaultService     defaultService;
    private final WalletSigner             walletSigner;
    private final Pkcs11HsmService         pkcs11HsmService;
    private final ApplicationEventPublisher eventPublisher;

    public WalletService(
            OperatorWalletRepository walletRepository,
            WalletStorage walletStorage,
            WalletDefaultService defaultService,
            WalletSigner walletSigner,
            Pkcs11HsmService pkcs11HsmService,
            ApplicationEventPublisher eventPublisher) {
        this.walletRepository   = walletRepository;
        this.walletStorage      = walletStorage;
        this.defaultService     = defaultService;
        this.walletSigner       = walletSigner;
        this.pkcs11HsmService   = pkcs11HsmService;
        this.eventPublisher = eventPublisher;
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
    public OperatorWallet generate(String name, WalletType type, UUID actorId, String actorRole) {
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

        eventPublisher.publishEvent(new WalletGeneratedEvent(wallet.getId(), actorId, actorRole));
        log.info("Generated {} wallet '{}': address={}", type, name, address);
        return wallet;
    }

    // ── Import raw ────────────────────────────────────────────────────────────

    public OperatorWallet importRaw(String name, WalletType type, String privateKeyHex, UUID actorId, String actorRole) {
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

        eventPublisher.publishEvent(new WalletImportedRawEvent(wallet.getId(), actorId, actorRole));
        log.info("Imported raw {} key as wallet '{}': address={}", type, name, address);
        return wallet;
    }

    // ── Import keystore ───────────────────────────────────────────────────────

    /**
     * Imports a Web3 Secret Storage v3 keystore JSON (EVM only) supplied by the operator.
     * The file is decrypted with {@code userPassword}, then re-encrypted under the master KEK.
     */
    public OperatorWallet importKeystore(String name, String keystoreJson, String userPassword, UUID actorId, String actorRole) {
        requireUniqueName(name);
        UUID id = UUID.randomUUID();
        String relativePath = walletStorage.importEvmKeystore(id, keystoreJson, userPassword);

        Credentials credentials = walletStorage.loadEvm(relativePath);
        String address = credentials.getAddress();

        OperatorWallet wallet = persist(id, name, WalletType.EVM, address, relativePath);
        defaultService.autoPromoteIfFirstOfType(wallet);

        eventPublisher.publishEvent(new WalletImportedKeystoreEvent(wallet.getId(), actorId, actorRole));
        log.info("Imported keystore as wallet '{}': address={}", name, address);
        return wallet;
    }

    /** Registers an EVM key which already exists in the instance's configured PKCS#11 token. */
    @Override
    public OperatorWallet attachHsm(String name, String keyAlias, String address,
                                    UUID actorId, String actorRole) {
        requireUniqueName(name);
        if (!pkcs11HsmService.isEnabled()) {
            throw new IllegalStateException("PKCS#11 HSM support is not enabled for this instance");
        }
        // A challenge proves that the alias really controls the supplied address before it can
        // become a chain default. No transaction or private-key export is involved.
        Pkcs11EvmSigner signer = new Pkcs11EvmSigner(pkcs11HsmService, keyAlias, address);
        signer.signDigest(org.web3j.crypto.Hash.sha3(
                ("registerwerk-hsm-enrollment:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        OperatorWallet wallet = new OperatorWallet();
        wallet.setName(name);
        wallet.setType(WalletType.EVM);
        wallet.setAddress(org.web3j.crypto.Keys.toChecksumAddress(address));
        wallet.setCustodyType(OperatorWallet.CustodyType.PKCS11);
        wallet.setKeyReference(keyAlias);
        wallet.setCreatedBy(actorId);
        OperatorWallet saved = walletRepository.save(wallet);
        defaultService.autoPromoteIfFirstOfType(saved);
        eventPublisher.publishEvent(new WalletGeneratedEvent(saved.getId(), actorId, actorRole));
        return saved;
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Exports the wallet as a Web3 Secret Storage v3 JSON encrypted with {@code exportPassword}.
     * Returns the JSON string; controller writes it as a file download.
     */
    public String exportKeystore(UUID walletId, String exportPassword, UUID actorId, String actorRole) {
        OperatorWallet wallet = getById(walletId);
        if (wallet.getCustodyType() == OperatorWallet.CustodyType.PKCS11) {
            throw new UnsupportedOperationException("HSM keys are non-exportable");
        }
        if (wallet.getType() != WalletType.EVM) {
            throw new UnsupportedOperationException("Keystore export is only supported for EVM wallets");
        }
        String json = walletStorage.exportEvmKeystore(wallet.getKeystorePath(), exportPassword);
        eventPublisher.publishEvent(new WalletExportedKeystoreEvent(walletId, actorId, actorRole));
        log.info("Exported keystore for wallet '{}'", wallet.getName());
        return json;
    }

    /**
     * Returns the raw private key hex. This is a dangerous operation; caller must
     * gate it behind an explicit confirmation and the result is audit-logged.
     */
    public String exportRaw(UUID walletId, UUID actorId, String actorRole) {
        OperatorWallet wallet = getById(walletId);
        if (wallet.getCustodyType() == OperatorWallet.CustodyType.PKCS11) {
            throw new UnsupportedOperationException("HSM keys are non-exportable");
        }
        String raw = wallet.getType() == WalletType.EVM
                ? walletStorage.exportEvmRaw(wallet.getKeystorePath())
                : java.util.HexFormat.of().formatHex(walletStorage.loadSolana(wallet.getKeystorePath()));

        eventPublisher.publishEvent(new WalletExportedRawEvent(walletId, actorId, actorRole));
        log.warn("RAW private key exported for wallet '{}' ({})", wallet.getName(), walletId);
        return raw;
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    public OperatorWallet rename(UUID walletId, String newName, UUID actorId, String actorRole) {
        OperatorWallet wallet = getById(walletId);
        String oldName = wallet.getName();
        requireUniqueName(newName);
        wallet.setName(newName);
        OperatorWallet saved = walletRepository.save(wallet);
        eventPublisher.publishEvent(new WalletRenamedEvent(walletId, actorId, actorRole));
        return saved;
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Deletes a wallet. Removes all chain defaults pointing to it first (the operator
     * is responsible for setting a replacement default before any chain operation runs).
     */
    public void delete(UUID walletId, UUID actorId, String actorRole) {
        OperatorWallet wallet = getById(walletId);
        defaultService.removeDefaultsForWallet(walletId);
        walletSigner.evict(walletId);
        if (wallet.getCustodyType() == OperatorWallet.CustodyType.SOFTWARE) {
            walletStorage.delete(wallet.getKeystorePath());
        }
        walletRepository.delete(wallet);
        eventPublisher.publishEvent(new WalletDeletedEvent(walletId, actorId, actorRole));
        log.info("Deleted wallet '{}' ({})", wallet.getName(), walletId);
    }

    // ── KEK rotation ──────────────────────────────────────────────────────────

    /**
     * Re-wraps a single wallet's DEK under whatever KEK version {@link WalletStorage}'s
     * provider currently resolves to. Intended for deliberate rotation (e.g. after a suspected
     * KEK compromise), not routine key-version bumps a KMS already handles transparently.
     *
     * @return true if a DEK was re-wrapped, false for a legacy wallet with no wrapped DEK
     */
    public boolean rotateKek(UUID walletId, UUID actorId, String actorRole) {
        OperatorWallet wallet = getById(walletId);
        if (wallet.getCustodyType() == OperatorWallet.CustodyType.PKCS11) {
            return false;
        }
        boolean rotated = walletStorage.rewrapDek(wallet.getKeystorePath(), wallet.getType() == WalletType.EVM);
        eventPublisher.publishEvent(new WalletKekRotatedEvent(walletId, actorId, actorRole, rotated));
        log.info("KEK rotation for wallet '{}' ({}): {}", wallet.getName(), walletId,
                rotated ? "rewrapped" : "skipped (legacy keystore, no wrapped DEK)");
        return rotated;
    }

    /**
     * Rotates every wallet's DEK in one pass — the bulk counterpart to {@link #rotateKek},
     * for an operator responding to a suspected KEK compromise across the whole fleet.
     *
     * @return the IDs of wallets that were actually rewrapped (excludes legacy wallets skipped)
     */
    public List<UUID> rotateAllKeks(UUID actorId, String actorRole) {
        List<UUID> rotatedIds = new java.util.ArrayList<>();
        for (OperatorWallet wallet : walletRepository.findAll()) {
            if (rotateKek(wallet.getId(), actorId, actorRole)) {
                rotatedIds.add(wallet.getId());
            }
        }
        return rotatedIds;
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
