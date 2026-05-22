package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigService;
import de.makibytes.registerwerk.wallet.api.ChainBalanceFetcher;
import de.makibytes.registerwerk.wallet.api.OperatorWallet;
import de.makibytes.registerwerk.wallet.api.WalletBalancePort;
import de.makibytes.registerwerk.wallet.web.dto.WalletBalanceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Fetches native cryptocurrency balances for an operator wallet across all active chains. */
@Service
public class WalletBalanceService implements WalletBalancePort {

    private static final Logger log = LoggerFactory.getLogger(WalletBalanceService.class);

    private final WalletService walletService;
    private final ChainConfigService chainConfigService;
    private final ChainBalanceFetcher chainBalanceFetcher;

    public WalletBalanceService(WalletService walletService,
                                 ChainConfigService chainConfigService,
                                 ChainBalanceFetcher chainBalanceFetcher) {
        this.walletService = walletService;
        this.chainConfigService = chainConfigService;
        this.chainBalanceFetcher = chainBalanceFetcher;
    }

    @Override
    public List<WalletBalanceResponse> getBalances(UUID walletId) {
        OperatorWallet wallet = walletService.getById(walletId);
        ChainConfig.ChainType targetType = wallet.getType() == OperatorWallet.WalletType.EVM
                ? ChainConfig.ChainType.EVM : ChainConfig.ChainType.SOLANA;

        List<ChainConfig> chains = chainConfigService.listEnabled().stream()
                .filter(c -> c.getChainType() == targetType).toList();

        List<WalletBalanceResponse> results = new ArrayList<>();
        for (ChainConfig chain : chains) {
            results.add(fetchBalance(wallet, chain));
        }
        return results;
    }

    private WalletBalanceResponse fetchBalance(OperatorWallet wallet, ChainConfig chain) {
        String symbol = nativeCurrencySymbol(chain);
        try {
            BigDecimal balance = chain.getChainType() == ChainConfig.ChainType.EVM
                    ? chainBalanceFetcher.getEvmBalance(wallet.getAddress(), chain.getIdentifier())
                    : chainBalanceFetcher.getSolanaBalance(wallet.getAddress(), chain.getIdentifier());
            return new WalletBalanceResponse(
                    chain.getId(), chain.getIdentifier(), chain.getDisplayName(), symbol, balance, null);
        } catch (Exception e) {
            log.warn("Failed to fetch balance for wallet {} on chain {}: {}",
                    wallet.getId(), chain.getIdentifier(), e.getMessage());
            return new WalletBalanceResponse(
                    chain.getId(), chain.getIdentifier(), chain.getDisplayName(), symbol, null, e.getMessage());
        }
    }

    private static String nativeCurrencySymbol(ChainConfig chain) {
        if (chain.getChainType() == ChainConfig.ChainType.SOLANA) return "SOL";
        String id = chain.getIdentifier().toUpperCase();
        if (id.contains("POLYGON") || id.contains("MATIC")) return "POL";
        if (id.contains("BNB") || id.contains("BSC")) return "BNB";
        if (id.contains("AVAX") || id.contains("AVALANCHE")) return "AVAX";
        return "ETH";
    }
}
