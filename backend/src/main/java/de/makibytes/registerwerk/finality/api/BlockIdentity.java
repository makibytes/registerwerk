package de.makibytes.registerwerk.finality.api;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Protocol-safe comparison helpers for block identities.
 *
 * <p>Hexadecimal identities with a {@code 0x} prefix are case-insensitive and are stored in
 * lower-case canonical form. Other protocols use identifiers whose alphabet may be
 * case-sensitive (for example Solana base58), so those values must be preserved and compared
 * exactly.
 */
public final class BlockIdentity {

    private static final Pattern PREFIXED_HEX = Pattern.compile("^0[xX][0-9a-fA-F]+$");

    private BlockIdentity() {
    }

    /** Returns the canonical persistence/comparison form without damaging non-hex identities. */
    public static String normalize(String blockHash) {
        return blockHash != null && PREFIXED_HEX.matcher(blockHash).matches()
                ? blockHash.toLowerCase(Locale.ROOT)
                : blockHash;
    }

    /** Compares block hashes using protocol-appropriate case semantics. */
    public static boolean sameHash(String left, String right) {
        return Objects.equals(normalize(left), normalize(right));
    }

    /**
     * Checks that a projection still represents the exact block occurrence which produced an
     * effect. A height match alone is insufficient because a replacement block can occupy the
     * same height.
     */
    public static boolean sameIncarnation(
            Long currentBlockNumber, String currentBlockHash, long effectBlockNumber, String effectBlockHash) {
        return currentBlockNumber != null
                && currentBlockNumber == effectBlockNumber
                && currentBlockHash != null
                && effectBlockHash != null
                && sameHash(currentBlockHash, effectBlockHash);
    }
}
