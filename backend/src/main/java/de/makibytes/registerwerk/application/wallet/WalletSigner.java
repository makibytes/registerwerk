package de.makibytes.registerwerk.application.wallet;

import de.makibytes.registerwerk.application.blockchain.ChainDescriptor;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.wallet.OperatorWallet;
import de.makibytes.registerwerk.domain.wallet.WalletChainDefault;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.OperatorWalletRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.WalletChainDefaultRepository;
import de.makibytes.registerwerk.infrastructure.wallet.WalletStorage;
import org.p2p.solanaj.core.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides signing credentials for the active default wallet of each chain.
 *
 * <p>Credentials are cached per wallet ID to avoid re-decrypting keystore files
 * on every transaction. The cache is invalidated by {@link #evict(UUID)} whenever
 * a wallet is deleted or its keystore is overwritten.
 */
@Component
public class WalletSigner {

    private static final Logger log = LoggerFactory.getLogger(WalletSigner.class);

    private final WalletChainDefaultRepository defaultRepository;
    private final OperatorWalletRepository walletRepository;
    private final WalletStorage walletStorage;

    private final ConcurrentHashMap<UUID, Credentials> evmCache    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Account>     solanaCache = new ConcurrentHashMap<>();

    public WalletSigner(
            WalletChainDefaultRepository defaultRepository,
            OperatorWalletRepository walletRepository,
            WalletStorage walletStorage) {
        this.defaultRepository = defaultRepository;
        this.walletRepository  = walletRepository;
        this.walletStorage     = walletStorage;
    }

    // ── EVM ───────────────────────────────────────────────────────────────────

    /**
     * Returns the signing credentials for the default EVM wallet of the given chain.
     *
     * @throws IllegalStateException if no default wallet is set for this chain
     */
    public Credentials credentialsForChain(UUID chainConfigId) {
        UUID walletId = resolveWalletId(chainConfigId);
        return evmCache.computeIfAbsent(walletId, id -> {
            OperatorWallet wallet = loadWallet(id);
            log.debug("Loading EVM credentials for wallet '{}' ({})", wallet.getName(), id);
            return walletStorage.loadEvm(wallet.getKeystorePath());
        });
    }

    /**
     * Returns the signing credentials for the default EVM wallet of the given chain descriptor.
     * Looks up the chain config by identifier ({@code CHAIN_NETWORK} format).
     *
     * @throws IllegalStateException if no default wallet is set
     */
    public Credentials credentialsForDescriptor(ChainDescriptor descriptor) {
        String identifier = descriptor.chain().name() + "_" + descriptor.network().name();
        WalletChainDefault match = defaultRepository.findByChainIdentifier(identifier)
                .orElseThrow(() -> new IllegalStateException(
                        "No default wallet for chain '" + identifier + "'. " +
                        "Please configure a wallet default via the Operator Portal → Wallets."));
        return credentialsForChain(match.getChainConfigId());
    }

    /**
     * Returns signing credentials from any configured EVM default wallet.
     * Used for chain-agnostic operations (e.g. ERC-3643 claim signing).
     *
     * @throws IllegalStateException if no EVM wallet defaults are configured
     */
    public Credentials credentialsForAnyEvm() {
        List<WalletChainDefault> defaults = defaultRepository.findAllEvmDefaults();
        if (defaults.isEmpty()) {
            throw new IllegalStateException(
                    "No EVM wallet default is configured. Please add a wallet and set it as default " +
                    "for at least one EVM chain via the Operator Portal → Wallets.");
        }
        return credentialsForChain(defaults.get(0).getChainConfigId());
    }

    // ── Solana ────────────────────────────────────────────────────────────────

    /**
     * Returns the Solana {@link Account} for the default wallet of the given chain.
     */
    public Account solanaAccountForChain(UUID chainConfigId) {
        UUID walletId = resolveWalletId(chainConfigId);
        return solanaCache.computeIfAbsent(walletId, id -> {
            OperatorWallet wallet = loadWallet(id);
            log.debug("Loading Solana account for wallet '{}' ({})", wallet.getName(), id);
            byte[] secretKey = walletStorage.loadSolana(wallet.getKeystorePath());
            return new Account(secretKey);
        });
    }

    // ── Cache management ──────────────────────────────────────────────────────

    /** Removes cached credentials for the given wallet, forcing a reload on next use. */
    public void evict(UUID walletId) {
        evmCache.remove(walletId);
        solanaCache.remove(walletId);
        log.debug("Evicted cached credentials for wallet {}", walletId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID resolveWalletId(UUID chainConfigId) {
        return defaultRepository.findByChainConfigId(chainConfigId)
                .map(d -> d.getWallet().getId())
                .orElseThrow(() -> new IllegalStateException(
                        "No default wallet is configured for chain " + chainConfigId +
                        ". Please set a default wallet via the Operator Portal → Wallets."));
    }

    private OperatorWallet loadWallet(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new EntityNotFoundException("OperatorWallet", walletId));
    }
}
