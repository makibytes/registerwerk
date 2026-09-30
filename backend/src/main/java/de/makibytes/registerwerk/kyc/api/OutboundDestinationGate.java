package de.makibytes.registerwerk.kyc.api;

import java.util.UUID;

/**
 * P4C-2: binds the destination of an operator/issuer chain operation that grants or moves
 * holdings (whitelist, mint, forced transfer, forced approve) to a screened register holder.
 *
 * <p>The address must resolve to an ACTIVE ({@code removed_at IS NULL}) {@code asset_holder} of the
 * SAME asset (nominee-pool holders included) whose legal entity is ACTIVE and KYC-APPROVED, has no
 * unresolved screening hit (entity or beneficial owner) and no §16 Sperrvermerk. Fail closed: an
 * unknown or non-compliant destination raises {@code AccessDeniedException} (403) with the reason.
 * There is deliberately no exception path in the interim (parked T4-04): a new party is onboarded
 * as a holder first.
 */
public interface OutboundDestinationGate {

    /** Holder resolved for confirmation echo in the audit trail / UI. */
    record ResolvedDestination(UUID holderId, UUID entityId, String holderName, String address) {}

    /**
     * @param purpose short label for the error message / audit (e.g. {@code "mint"})
     * @return the resolved holder, or {@code null} only when the gate is disabled by configuration
     */
    ResolvedDestination require(UUID assetId, String address, String purpose);
}
