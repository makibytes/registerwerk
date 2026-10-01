package de.makibytes.registerwerk.travelrule.api;

import java.util.UUID;

/**
 * Verifies an EIP-191 {@code personal_sign} signature for the Art. 14(5) wallet-control proof. Declared
 * here and implemented by the {@code orgidentity} module on top of its shared signature verifier, so that
 * {@code travelrule} stays free of a dependency that would close the blockchain -> travelrule -> orgidentity
 * -> blockchain cycle.
 */
public interface WalletSignaturePort {

    /** @throws IllegalArgumentException if the signature does not verify against {@code claimedWallet} */
    void verifyPersonalSign(UUID chainConfigId, String message, String signatureHex, String claimedWallet);
}
