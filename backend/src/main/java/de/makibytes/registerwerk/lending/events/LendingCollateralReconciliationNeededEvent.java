package de.makibytes.registerwerk.lending.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Fired when an operator's forced-transfer/force-burn on a token deployment moves collateral
 * out of a known {@code EwpgRepoMarket}'s own balance — the market's
 * internal {@code positions} accounting has no way to observe that move on its own, and
 * {@code EwpgRepoMarket.reconcileCollateral} needs an operator to manually attribute the
 * reduction to the affected borrower (the corrected amount can only be known from an off-chain
 * reconciliation of the specific forced-transfer transaction — never auto-derived on-chain, per
 * that function's own NatSpec). This event only surfaces the desync for operator follow-up; it
 * deliberately does not call {@code reconcileCollateral} itself.
 */
public record LendingCollateralReconciliationNeededEvent(
        UUID marketId, String marketAddress, String tokenAdminMethod, String toAddress, String amount)
        implements AuditableEvent {

    public String eventType()   { return "LENDING_COLLATERAL_RECONCILIATION_NEEDED"; }
    public String subjectType() { return "LendingMarket"; }
    public UUID   subjectId()   { return marketId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() {
        return Map.of(
                "marketAddress", marketAddress != null ? marketAddress : "",
                "tokenAdminMethod", tokenAdminMethod != null ? tokenAdminMethod : "",
                "toAddress", toAddress != null ? toAddress : "",
                "amount", amount != null ? amount : ""
        );
    }
}
