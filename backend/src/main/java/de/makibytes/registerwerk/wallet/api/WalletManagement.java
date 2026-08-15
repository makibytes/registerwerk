package de.makibytes.registerwerk.wallet.api;

import java.util.UUID;

/** Public wallet-module boundary for enrolling an existing opaque PKCS#11 key. */
public interface WalletManagement {
    OperatorWallet attachHsm(String name, String keyAlias, String address,
                             UUID actorId, String actorRole);
}
