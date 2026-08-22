package de.makibytes.registerwerk.corporateactions.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/**
 * The issuer's attestation that the underlying obligation/cash-leg for a corporate action's
 * settlement is ready — the first of the two required parties before an operator can confirm
 * settlement (see {@code CorporateActionService.attestSettlementAsIssuer}). Deliberately not
 * step-up-gated: {@code frontend-customer} has no step-up UI today (see the portfolio plan's
 * confirmed deferral) — this is an authenticated action (the caller must be the asset's issuer),
 * not an unauthenticated one.
 *
 * @param attestationReference the issuer's own reference for the obligation (e.g. a payment
 *                             instruction id) — free text, but required, so an empty attestation
 *                             can't be submitted by accident
 * @param acknowledged         must be {@code true} — an explicit, typed confirmation that the
 *                             issuer has reviewed and is ready to proceed, not just a button click
 */
public record IssuerAttestationRequest(@NotBlank String attestationReference, boolean acknowledged) {
    @AssertTrue(message = "acknowledged must be true")
    public boolean isAcknowledged() {
        return acknowledged;
    }
}
