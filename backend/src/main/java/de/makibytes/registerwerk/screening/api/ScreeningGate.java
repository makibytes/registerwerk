package de.makibytes.registerwerk.screening.api;

import java.util.UUID;

/**
 * Public API façade for the screening module.
 * Used by other modules (kyc, onboarding) to check screening status without
 * crossing into screening/internal/.
 */
public interface ScreeningGate {

    /**
     * Returns true if approval must be blocked for this entity (fail closed):
     * the entity has never been screened, the latest run is PENDING/ERROR/REJECTED,
     * or the latest run produced a HIT with at least one unreviewed (accepted=null) match.
     * A compliance officer must resolve the condition before KYC can be approved.
     */
    boolean hasUnresolvedHit(UUID entityId);

    /**
     * Returns true if any beneficial owner / natural person linked to the entity
     * has a blocking screening condition (same fail-closed semantics as
     * {@link #hasUnresolvedHit(UUID)}).
     */
    boolean hasUnresolvedBeneficialOwnerHit(UUID entityId);
}
