package de.makibytes.registerwerk.screening.api;

import java.util.UUID;

/**
 * Public API façade for the screening module.
 * Used by other modules (kyc, onboarding) to check screening status, and to trigger a
 * screening run for a natural person, without crossing into screening/internal/.
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

    /**
     * Triggers a screening run for a natural person against every configured provider —
     * synchronous, same as an operator-initiated manual screen. Intended callers: {@code kyc}'s
     * beneficial-owner registration (immediately after a UBO is added — GwG §11) and its
     * periodic re-screening job (GwG §10 ongoing monitoring). The module boundary only allows
     * {@code kyc → screening}, not the reverse, so this method (not an event listener inside
     * {@code screening}) is how the trigger is wired — the caller already has the person's
     * current name/country on hand from its own {@code NaturalPerson} record.
     *
     * @param trigger why this screening is happening, for the {@code ScreeningRun} audit trail
     */
    void screenNaturalPerson(UUID naturalPersonId, String fullName, String countryCode, ScreeningTrigger trigger);
}
