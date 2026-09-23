package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;

import java.util.Locale;

/**
 * Maps {@code EwpgERC4626}/{@code EwpgERC7540} {@code require} strings (surfaced by a pre-flight
 * {@code eth_call}) to the exception the API layer turns into a meaningful response, instead of a
 * submitted tx that later just shows up as FAILED.
 */
final class VaultRevertReasons {

    private VaultRevertReasons() {}

    static RuntimeException translate(String reason, String action) {
        String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
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
}
