package de.makibytes.registerwerk.wallet.api;

import de.makibytes.registerwerk.wallet.web.dto.WalletBalanceResponse;

import java.util.List;
import java.util.UUID;

/** Port for querying on-chain wallet balances. Implemented by wallet/internal/WalletBalanceService. */
public interface WalletBalancePort {
    List<WalletBalanceResponse> getBalances(UUID walletId);
}
