package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.wallet.events.WalletDefaultChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.wallet.api.OperatorWallet;
import de.makibytes.registerwerk.wallet.api.WalletChainDefault;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.wallet.api.OperatorWalletRepository;
import de.makibytes.registerwerk.wallet.api.WalletChainDefaultRepository;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages the {@code wallet_chain_default} table: which wallet is the active signer per chain.
 */
@Service
@Transactional
public class WalletDefaultService {

    private static final Logger log = LoggerFactory.getLogger(WalletDefaultService.class);

    private final WalletChainDefaultRepository defaultRepository;
    private final OperatorWalletRepository     walletRepository;
    private final ChainConfigRepository        chainConfigRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final WalletSigner                 walletSigner;

    public WalletDefaultService(
            WalletChainDefaultRepository defaultRepository,
            OperatorWalletRepository walletRepository,
            ChainConfigRepository chainConfigRepository,
            ApplicationEventPublisher eventPublisher,
            WalletSigner walletSigner) {
        this.defaultRepository     = defaultRepository;
        this.walletRepository      = walletRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.eventPublisher        = eventPublisher;
        this.walletSigner          = walletSigner;
    }

    @Transactional(readOnly = true)
    public List<WalletChainDefault> listAll() {
        return defaultRepository.findAllWithAssociations();
    }

    /**
     * Sets {@code walletId} as the default signer for {@code chainConfigId}.
     *
     * <p>Validates that the wallet type matches the chain type (EVM↔EVM, SOLANA↔SOLANA).
     */
    public WalletChainDefault setDefault(
            UUID chainConfigId, UUID walletId, UUID actorId, String actorRole, UUID dualControlApproverId) {
        ChainConfig chain = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
        OperatorWallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new EntityNotFoundException("OperatorWallet", walletId));

        OperatorWallet.WalletType requiredType = chain.getChainType() == ChainConfig.ChainType.EVM
                ? OperatorWallet.WalletType.EVM : OperatorWallet.WalletType.SOLANA;
        if (wallet.getType() != requiredType) {
            throw new IllegalArgumentException(
                    "Wallet type mismatch: chain requires " + requiredType +
                    " but wallet '" + wallet.getName() + "' is " + wallet.getType());
        }

        UUID previousWalletId = defaultRepository.findByChainConfigId(chainConfigId)
                .map(d -> d.getWallet().getId()).orElse(null);

        WalletChainDefault record = defaultRepository.findByChainConfigId(chainConfigId)
                .orElseGet(() -> {
                    WalletChainDefault d = new WalletChainDefault();
                    d.setChainConfigId(chainConfigId);
                    return d;
                });
        record.setWallet(wallet);
        WalletChainDefault saved = defaultRepository.save(record);

        // Evict old wallet from signer cache so next call reloads from the new default
        if (previousWalletId != null && !previousWalletId.equals(walletId)) {
            walletSigner.evict(previousWalletId);
        }

        eventPublisher.publishEvent(
                new WalletDefaultChangedEvent(walletId, actorId, actorRole, chainConfigId, dualControlApproverId));
        log.info("Set default wallet for chain '{}' → '{}' ({})",
                chain.getIdentifier(), wallet.getName(), walletId);
        return saved;
    }

    /**
     * P4C-5: fresh-install bootstrap ONLY. The first wallet ever created is promoted to default for
     * chains without one; once {@code wallet_bootstrap_marker} exists (any wallet was ever created,
     * even if deleted since) this is a no-op, so an imported or generated key can never silently
     * become a signer — default switching always goes through {@link #setDefault} (4-eyes + event).
     */
    public void bootstrapDefaultIfFirstWalletEver(OperatorWallet wallet) {
        if (walletRepository.bootstrapCompleted()) {
            log.info("Wallet '{}' created; not auto-promoted (bootstrap already completed) — set the "
                    + "chain default explicitly with 4-eyes.", wallet.getName());
            return;
        }
        walletRepository.markBootstrapCompleted();
        autoPromoteIfFirstOfType(wallet);
    }

    /**
     * Promotes {@code wallet} as the default for all chains of its type that have no default set
     * yet. Only reachable through {@link #bootstrapDefaultIfFirstWalletEver}.
     */
    void autoPromoteIfFirstOfType(OperatorWallet wallet) {
        ChainConfig.ChainType targetType = wallet.getType() == OperatorWallet.WalletType.EVM
                ? ChainConfig.ChainType.EVM : ChainConfig.ChainType.SOLANA;

        List<ChainConfig> chains = chainConfigRepository.findByChainTypeAndEnabledTrue(targetType);
        for (ChainConfig chain : chains) {
            if (defaultRepository.findByChainConfigId(chain.getId()).isEmpty()) {
                WalletChainDefault d = new WalletChainDefault();
                d.setChainConfigId(chain.getId());
                d.setWallet(wallet);
                defaultRepository.save(d);
                log.info("Bootstrap: promoted wallet '{}' as default for chain '{}'",
                        wallet.getName(), chain.getIdentifier());
            }
        }
    }

    @Transactional(readOnly = true)
    public List<UUID> findDefaultChainIds(UUID walletId) {
        return defaultRepository.findByWallet_Id(walletId).stream().map(WalletChainDefault::getChainConfigId).toList();
    }

    /**
     * Removes all chain defaults pointing to this wallet.
     * Called before deleting a wallet to satisfy the FK RESTRICT.
     */
    public void removeDefaultsForWallet(UUID walletId) {
        List<WalletChainDefault> defaults = defaultRepository.findByWallet_Id(walletId);
        if (!defaults.isEmpty()) {
            defaultRepository.deleteAll(defaults);
            walletSigner.evict(walletId);
        }
    }
}
