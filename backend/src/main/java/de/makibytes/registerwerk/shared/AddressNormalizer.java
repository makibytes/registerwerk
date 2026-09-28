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
}
