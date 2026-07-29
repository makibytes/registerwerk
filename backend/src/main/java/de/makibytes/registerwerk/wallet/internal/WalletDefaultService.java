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
import java.util.Map;
import java.util.Optional;
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

    @Transactional(readOnly = true)
    public Optional<WalletChainDefault> findForChain(UUID chainConfigId) {
        return defaultRepository.findByChainConfigId(chainConfigId);
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
     * Auto-promotes {@code wallet} as the default for all chains of its type that have no
     * default set yet. Called after a wallet is created/imported.
     */
    public void autoPromoteIfFirstOfType(OperatorWallet wallet) {
        ChainConfig.ChainType targetType = wallet.getType() == OperatorWallet.WalletType.EVM
                ? ChainConfig.ChainType.EVM : ChainConfig.ChainType.SOLANA;

        List<ChainConfig> chains = chainConfigRepository.findByChainTypeAndEnabledTrue(targetType);
        for (ChainConfig chain : chains) {
            if (defaultRepository.findByChainConfigId(chain.getId()).isEmpty()) {
                WalletChainDefault d = new WalletChainDefault();
                d.setChainConfigId(chain.getId());
                d.setWallet(wallet);
                defaultRepository.save(d);
                log.info("Auto-promoted wallet '{}' as default for chain '{}'",
                        wallet.getName(), chain.getIdentifier());
            }
        }
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
