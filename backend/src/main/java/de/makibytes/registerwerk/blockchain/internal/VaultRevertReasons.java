package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;

import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps {@code EwpgERC4626}/{@code EwpgERC7540} {@code require} strings (surfaced by a pre-flight
 * {@code eth_call}) to the exception the API layer turns into a meaningful response, instead of a
 * submitted tx that later just shows up as FAILED.
 */
final class VaultRevertReasons {

    /** 4-byte selector of {@code NavNotStruckAfterDealingPoint(uint256,uint256,uint256)} (forward pricing, T1-07). */
    private static final String NAV_NOT_STRUCK_SELECTOR = Hash.sha3String(
            "NavNotStruckAfterDealingPoint(uint256,uint256,uint256)").substring(2, 10).toLowerCase(Locale.ROOT);
    /** The revert data as some nodes print it: selector, optional separator, then the three 32-byte words. */
    private static final Pattern NAV_NOT_STRUCK_DATA = Pattern.compile(
            "0x" + NAV_NOT_STRUCK_SELECTOR + "[:\\s]*(?:0x)?([0-9a-f]{192})?");
    /** The decoded form some tools print: {@code NavNotStruckAfterDealingPoint(7, 1800014400, 1799971200)}. */
    private static final Pattern NAV_NOT_STRUCK_NAMED = Pattern.compile(
            "navnotstruckafterdealingpoint\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");

    private VaultRevertReasons() {}

    static RuntimeException translate(String reason, String action) {
        String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        RuntimeException waiting = navNotStruckAfterDealingPoint(r, action);
        if (waiting != null) {
            return waiting;
        }
        if (r.contains("refund recipient is frozen")) {
            return new ComplianceGateException("The deposit's payer (the refund recipient) is frozen on the vault — "
                    + action + " is refused and the escrow stays in the vault (freeze-in-place). Release requires "
                    + "unfreezing the address or a registry force-cancel on a legal basis.");
        }
        if (r.contains("owner is frozen")) {
            return new ComplianceGateException("The request owner is frozen on the vault — " + action
                    + " is refused and the escrow stays in the vault (freeze-in-place). Release requires "
                    + "unfreezing the address or a registry force-cancel on a legal basis.");
        }
        if (r.contains("payer is frozen") || r.contains("receiver is frozen") || r.contains("caller is frozen")) {
            return new ComplianceGateException("A cash-leg party is frozen on the vault — " + action + " is refused ("
                    + reason + ").");
        }
        if (r.contains("insufficient settled liquidity")) {
            return new InvalidStateTransitionException("The vault's settled liquidity (its balance excluding pending "
                    + "subscriptions) does not cover this redemption at the current NAV — " + action
                    + " is refused until liquidity is added.");
        }
        if (r.contains("vault custody is not force-movable")) {
            return new IllegalArgumentException("The vault's own address holds other investors' escrowed redeem "
                    + "shares and cannot be the subject of a forced operation — force-cancel the individual "
                    + "request instead.");
        }
        if (r.contains("invalid destination")) {
            return new IllegalArgumentException("Force-cancel destination must be a non-zero address other than "
                    + "the vault itself.");
        }
        if (r.contains("settlement delay not elapsed")) {
            return new InvalidStateTransitionException("The vault's minimum settlement delay has not elapsed for this "
                    + "request yet — " + action + " is refused for now.");
        }
        if (r.contains("nav not struck")) {
            return new InvalidStateTransitionException("No NAV has been struck on the vault — strike a NAV before "
                    + action + ".");
        }
        if (r.contains("not pending")) {
            return new InvalidStateTransitionException("The request is no longer pending on-chain (already fulfilled "
                    + "or cancelled) — the register will catch up once the resolving event is ingested.");
        }
        return new IllegalStateException(action + " would revert on-chain: " + reason);
    }

    /**
     * Forward pricing (T1-07): a request may only settle at a NAV struck at or after its dealing point. A
     * fulfilment attempted earlier is not a failure — it is waiting for the next NAV strike. Recognised by the
     * error name or by its selector (+ data) in whatever form the node prints a custom error.
     */
    private static RuntimeException navNotStruckAfterDealingPoint(String lowerReason, String action) {
        BigInteger requestId = null;
        BigInteger dealingPoint = null;
        BigInteger struckAt = null;
        Matcher named = NAV_NOT_STRUCK_NAMED.matcher(lowerReason);
        Matcher raw = NAV_NOT_STRUCK_DATA.matcher(lowerReason);
        if (named.find()) {
            requestId = new BigInteger(named.group(1));
            dealingPoint = new BigInteger(named.group(2));
            struckAt = new BigInteger(named.group(3));
        } else if (raw.find()) {
            String words = raw.group(1);
            if (words != null) {
                requestId = new BigInteger(words.substring(0, 64), 16);
                dealingPoint = new BigInteger(words.substring(64, 128), 16);
                struckAt = new BigInteger(words.substring(128, 192), 16);
            }
        } else if (!lowerReason.contains("navnotstruckafterdealingpoint")) {
            return null;
        }
        StringBuilder message = new StringBuilder(requestId == null ? "The request" : "Request #" + requestId)
                .append(" is waiting for the next NAV strike: ");
        if (dealingPoint != null && dealingPoint.bitLength() < 63 && struckAt.bitLength() < 63) {
            message.append("its dealing point is ").append(Instant.ofEpochSecond(dealingPoint.longValue())).append(" and ")
                    .append(struckAt.signum() == 0 ? "no NAV has been struck on the vault yet"
                            : "the latest NAV was struck at " + Instant.ofEpochSecond(struckAt.longValue()));
        } else {
            message.append("the latest struck NAV predates its dealing point");
        }
        return new InvalidStateTransitionException(message.append(" — ").append(action)
                .append(" is refused for now. Strike a NAV after the dealing point; nothing has failed.").toString());
    }
}
