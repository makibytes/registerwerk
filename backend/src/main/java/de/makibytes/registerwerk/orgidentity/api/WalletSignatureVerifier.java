package de.makibytes.registerwerk.orgidentity.api;

import java.util.UUID;

/**
 * Verifies that an EIP-191 {@code personal_sign} signature was produced by a claimed wallet
 * address — supporting both plain EOAs (ECDSA recovery) and smart-contract wallets (ERC-1271
 * {@code isValidSignature}). The claimed address's on-chain code is checked to decide which
 * path applies, so an EIP-7702-delegated EOA or an ERC-4337 account (both contract-coded at
 * their own address) verifies exactly like a Safe or any other smart-contract wallet, with no
 * caller-side branching required.
 *
 * <p>Shared by {@code orgidentity} (member-wallet binding) and {@code marketplace} (manifest
 * signing), which previously carried near-duplicate ECDSA-only recovery logic — consolidating
 * here means smart-account support lands once for both call sites instead of drifting apart.
 *
 * <p>This verifier accepts the {@code personal_sign} wire format; it does not accept EIP-712
 * typed-data signatures.
 */
public interface WalletSignatureVerifier {

    /**
     * @throws IllegalArgumentException if the signature does not verify against {@code claimedWallet}
     *                                  on {@code chainConfigId}
     */
    void verifyPersonalSign(UUID chainConfigId, String message, String signatureHex, String claimedWallet);
}
