package de.makibytes.registerwerk.application.wallet;

import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.chain.ChainConfig;
import de.makibytes.registerwerk.domain.wallet.OperatorWallet;
import de.makibytes.registerwerk.domain.wallet.WalletChainDefault;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.ChainConfigRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.OperatorWalletRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.WalletChainDefaultRepository;
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
    private final AuditEventPublisher          auditEventPublisher;
    private final WalletSigner                 walletSigner;

    public WalletDefaultService(
            WalletChainDefaultRepository defaultRepository,
            OperatorWalletRepository walletRepository,
            ChainConfigRepository chainConfigRepository,
            AuditEventPublisher auditEventPublisher,
            WalletSigner walletSigner) {
        this.defaultRepository     = defaultRepository;
        this.walletRepository      = walletRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.auditEventPublisher   = auditEventPublisher;
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
    public WalletChainDefault setDefault(UUID chainConfigId, UUID walletId) {
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

        auditEventPublisher.publish(
                "WALLET_DEFAULT_CHANGED", "WalletChainDefault", chainConfigId, null, null,
                Map.of("chainIdentifier", chain.getIdentifier(),
                       "walletId", walletId.toString(),
                       "walletName", wallet.getName()));
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
