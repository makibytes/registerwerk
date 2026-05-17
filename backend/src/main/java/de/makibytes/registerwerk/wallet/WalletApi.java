package de.makibytes.registerwerk.wallet;

import de.makibytes.registerwerk.wallet.api.OperatorWallet;

import java.util.List;
import java.util.UUID;

/** Public API for operator wallet queries. */
public interface WalletApi {

    List<OperatorWallet> listWallets();

    java.util.Optional<OperatorWallet> findWallet(UUID id);
}
