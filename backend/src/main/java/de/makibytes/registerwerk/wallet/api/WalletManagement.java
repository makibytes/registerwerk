package de.makibytes.registerwerk.wallet.api;

import java.util.UUID;

/** Public wallet-module boundary for enrolling an existing opaque PKCS#11 or cloud-KMS key. */
public interface WalletManagement {
    OperatorWallet attachHsm(String name, String keyAlias, String address,
                             UUID actorId, String actorRole);

    /**
     * Registers an existing non-exportable cloud-KMS secp256k1 key (T7-05). The address is derived
     * from the KMS public key; a supplied {@code expectedAddress} must match it.
     */
    OperatorWallet attachKms(String name, String keyReference, String expectedAddress,
                             UUID actorId, String actorRole);
}
