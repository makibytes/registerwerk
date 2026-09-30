package de.makibytes.registerwerk.wallet.api;

/**
 * P4C-5: lets the wallet module ask whether an operator key has ever been used on chain, without
 * depending on {@code blockchain}. A key that signed any chain transaction may hold on-chain
 * authority (deployer / registry / claim-issuer management key / role admin), so deleting it would
 * strand that authority — deletion is refused (conservative rule while no handover tooling exists).
 */
public interface WalletUsagePort {

    /** @return true if {@code address} appears as the sender of any recorded chain submission */
    boolean hasSignedOnChain(String address);
}
