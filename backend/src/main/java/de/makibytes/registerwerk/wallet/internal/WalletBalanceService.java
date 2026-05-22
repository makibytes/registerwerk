package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.chain.api.ChainConfigService;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.wallet.api.OperatorWallet;
import de.makibytes.registerwerk.wallet.web.dto.WalletBalanceResponse;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.rpc.RpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches native cryptocurrency balances for an operator wallet across all active chains.
 */
@Service
public class WalletBalanceService {

    private static final Logger log = LoggerFactory.getLogger(WalletBalanceService.class);

    private static final BigDecimal WEI_PER_ETH     = BigDecimal.TEN.pow(18);
    private static final BigDecimal LAMPORTS_PER_SOL = BigDecimal.valueOf(1_000_000_000L);

    private final WalletService             walletService;
    private final ChainConfigService        chainConfigService;
    private final BlockchainClientRegistry  clientRegistry;

    public WalletBalanceService(
            WalletService walletService,
            ChainConfigService chainConfigService,
            BlockchainClientRegistry clientRegistry) {
        this.walletService      = walletService;
        this.chainConfigService = chainConfigService;
        this.clientRegistry     = clientRegistry;
    }

    public List<WalletBalanceResponse> getBalances(java.util.UUID walletId) {
        OperatorWallet wallet = walletService.getById(walletId);
        ChainConfig.ChainType targetType = wallet.getType() == OperatorWallet.WalletType.EVM
                ? ChainConfig.ChainType.EVM
                : ChainConfig.ChainType.SOLANA;

        List<ChainConfig> chains = chainConfigService.listEnabled().stream()
                .filter(c -> c.getChainType() == targetType)
                .toList();

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
                    ? fetchEvmBalance(wallet.getAddress(), chain.getIdentifier())
                    : fetchSolanaBalance(wallet.getAddress(), chain.getIdentifier());

            return new WalletBalanceResponse(
                    chain.getId(), chain.getIdentifier(), chain.getDisplayName(), symbol, balance, null);
        } catch (Exception e) {
            log.warn("Failed to fetch balance for wallet {} on chain {}: {}",
                    wallet.getId(), chain.getIdentifier(), e.getMessage());
            return new WalletBalanceResponse(
                    chain.getId(), chain.getIdentifier(), chain.getDisplayName(), symbol, null, e.getMessage());
        }
    }

    private BigDecimal fetchEvmBalance(String address, String chainIdentifier) throws Exception {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainIdentifier);
        BigInteger wei = web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST)
                .send().getBalance();
        return new BigDecimal(wei).divide(WEI_PER_ETH, 8, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal fetchSolanaBalance(String address, String chainIdentifier) throws Exception {
        RpcClient client = clientRegistry.getSolanaClientByIdentifier(chainIdentifier);
        long lamports = client.getApi().getBalance(new PublicKey(address));
        return BigDecimal.valueOf(lamports).divide(LAMPORTS_PER_SOL, 8, java.math.RoundingMode.HALF_UP);
    }

    private static String nativeCurrencySymbol(ChainConfig chain) {
        if (chain.getChainType() == ChainConfig.ChainType.SOLANA) return "SOL";
        String id = chain.getIdentifier().toUpperCase();
        if (id.contains("POLYGON") || id.contains("MATIC")) return "POL";
        if (id.contains("BNB") || id.contains("BSC"))        return "BNB";
        if (id.contains("AVAX") || id.contains("AVALANCHE")) return "AVAX";
        return "ETH";
    }
}
