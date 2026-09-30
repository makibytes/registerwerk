package de.makibytes.registerwerk.shared;

import java.util.Locale;

/**
 * Canonical form of an on-chain address for register storage and lookup, shared by every
 * module that compares wallet addresses — including {@code kyc}, which must not depend on
 * {@code blockchain} ({@code blockchain.api.EvmUtils#normalizeAddress} delegates here).
 *
 * <p>Only 0x-prefixed hex addresses (EVM, Starknet) are lowercased: they are case-insensitive
 * on-chain, and lowercase is what the indexer and the register store. Solana (base58) and
 * Stellar (base32) addresses are case-sensitive, so anything else is only trimmed.
 */
public final class AddressNormalizer {

    private AddressNormalizer() {
    }

    public static String normalize(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        return trimmed.regionMatches(true, 0, "0x", 0, 2)
                ? trimmed.toLowerCase(Locale.ROOT)
                : trimmed;
    }

    /**
     * EIP-55 check for operator-entered EVM addresses (P4C-2): a mixed-case 0x address must equal
     * its checksum form, otherwise a typo would silently address a different account. All-lower and
     * all-upper hex carry no checksum and are accepted here (the destination gate then requires the
     * address to resolve to a registered holder). Non-EVM strings are not checked.
     *
     * @throws IllegalArgumentException (400) on a checksum mismatch
     */
    public static void requireValidChecksum(String address) {
        if (address == null) {
            return;
        }
        String a = address.trim();
        if (!a.matches("0x[0-9a-fA-F]{40}")) {
            return;
        }
        String hex = a.substring(2);
        if (hex.equals(hex.toLowerCase(Locale.ROOT)) || hex.equals(hex.toUpperCase(Locale.ROOT))) {
            return;
        }
        if (!org.web3j.crypto.Keys.toChecksumAddress(a).equals(a)) {
            throw new IllegalArgumentException("Address " + a + " fails the EIP-55 checksum — check for typos.");
        }
    }
}
