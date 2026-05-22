package de.makibytes.registerwerk.wallet.api;

import java.math.BigDecimal;

/** Port for querying on-chain native currency balances. Implemented in blockchain module. */
public interface ChainBalanceFetcher {
    BigDecimal getEvmBalance(String walletAddress, String chainIdentifier) throws Exception;
    BigDecimal getSolanaBalance(String walletAddress, String chainIdentifier) throws Exception;
}
