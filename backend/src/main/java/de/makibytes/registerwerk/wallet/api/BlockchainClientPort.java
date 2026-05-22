package de.makibytes.registerwerk.wallet.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Port for querying on-chain balances. Implemented in blockchain module. */
public interface BlockchainClientPort {
    record ChainBalance(String chainIdentifier, String address, String symbol, BigDecimal balance) {}
    List<ChainBalance> getWalletBalances(UUID walletId, String walletAddress, List<String> chainIdentifiers);
}
