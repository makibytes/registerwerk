package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmission.ErrorClass;

import java.util.Locale;

/**
 * Classifies an {@code eth_sendRawTransaction} failure (P4B-4). Node software words these
 * differently (geth, erigon, besu, reth, hardhat), so this is deliberate substring matching over
 * the lower-cased message; anything unrecognised is {@link ErrorClass#OTHER} (transient, retried
 * with back-off). Permanent classes can never succeed by retrying the identical bytes.
 */
final class BroadcastErrorClassifier {

    private BroadcastErrorClassifier() {}

    static ErrorClass classify(String message) {
        if (message == null || message.isBlank()) {
            return ErrorClass.OTHER;
        }
        String m = message.toLowerCase(Locale.ROOT);
        // Order matters: "replacement transaction underpriced" must not be read as INSUFFICIENT_FUNDS etc.
        if (m.contains("nonce too low") || m.contains("nonce is too low") || m.contains("invalid nonce")
                || m.contains("already been used")) {
            return ErrorClass.NONCE_LOW;
        }
        if (m.contains("max fee per gas less than block base fee") || m.contains("fee cap less than block base fee")
                || m.contains("below base fee") || m.contains("basefee") || m.contains("base fee")) {
            return ErrorClass.BASE_FEE;
        }
        if (m.contains("underpriced") || m.contains("fee too low") || m.contains("gas price below minimum")
                || m.contains("gas price too low") || m.contains("tip too low") || m.contains("max priority fee per gas")) {
            return ErrorClass.UNDERPRICED;
        }
        if (m.contains("insufficient funds") || m.contains("insufficient balance")
                || m.contains("gas required exceeds allowance") || m.contains("not enough funds")) {
            return ErrorClass.INSUFFICIENT_FUNDS;
        }
        if (m.contains("intrinsic gas too low") || m.contains("exceeds block gas limit")
                || m.contains("transaction gas limit") || m.contains("invalid sender")
                || m.contains("invalid signature") || m.contains("invalid chain id")
                || m.contains("invalid chainid") || m.contains("oversized data")
                || m.contains("transaction type not supported") || m.contains("rlp:")
                || m.contains("invalid transaction") || m.contains("malformed")) {
            return ErrorClass.INVALID;
        }
        if (m.contains("connection") || m.contains("timeout") || m.contains("timed out")
                || m.contains("unavailable") || m.contains("502") || m.contains("503") || m.contains("504")
                || m.contains("unreachable") || m.contains("rate limit") || m.contains("too many requests")) {
            return ErrorClass.TRANSPORT;
        }
        return ErrorClass.OTHER;
    }

    /** Classes for which retrying the identical bytes cannot ever succeed. */
    static boolean isPermanent(ErrorClass errorClass) {
        return errorClass == ErrorClass.INVALID || errorClass == ErrorClass.NONCE_LOW;
    }

    /** Classes a higher fee at the same nonce can fix. */
    static boolean isFeeRelated(ErrorClass errorClass) {
        return errorClass == ErrorClass.UNDERPRICED || errorClass == ErrorClass.BASE_FEE;
    }
}
