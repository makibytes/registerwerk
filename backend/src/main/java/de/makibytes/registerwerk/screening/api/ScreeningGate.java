package de.makibytes.registerwerk.screening.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Public API façade for the screening module.
 * Used by other modules (kyc, onboarding) to check screening status, and to trigger a
 * screening run for a natural person, without crossing into screening/internal/.
 */
public interface ScreeningGate {

    /**
     * Returns true if approval must be blocked for this entity (fail closed):
     * the entity has never been screened, the latest run is PENDING/REJECTED, the latest run
     * produced a HIT with at least one hit that still holds the gate (not accepted as a false positive
     * and not covered by an unexpired EDD approval), or the latest run ERRORed and no recent good
     * result can be relied on. A failed run does not replace a good result younger than
     * {@code registerwerk.screening.stale-clear-grace-hours} (counted from the first failed run) and
     * {@code stale-clear-max-age-hours}; see {@link #isRelyingOnStaleResult}. A compliance officer
     * must resolve the condition before KYC can be approved.
     */
    boolean hasUnresolvedHit(UUID entityId);

    /**
     * Returns true if any beneficial owner / natural person linked to the entity
     * has a blocking screening condition (same fail-closed semantics as
     * {@link #hasUnresolvedHit(UUID)}). Every currently recorded beneficial owner counts, including one
     * that was never screened; a ceased beneficial owner keeps counting while their latest run is
     * unresolved, so ceasing a record cannot make a hit disappear.
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

    /**
     * Same fail-closed evaluation for one natural person across all providers (never screened, ERROR
     * beyond the grace window, open hit). For callers that must refuse an action while the person's
     * screening is unresolved, e.g. ceasing a beneficial owner.
     */
    boolean hasUnresolvedHitForPerson(UUID naturalPersonId);

    /**
     * True if the person's latest run at any provider contains a hit that was confirmed as a PEP
     * (resolution CONFIRMED_PEP), whether or not an EDD approval currently covers it.
     */
    boolean hasConfirmedPep(UUID naturalPersonId);

    /**
     * Records that enhanced due diligence for the person's confirmed PEP was approved (the approval
     * record and its review cadence are owned by the caller). Until {@code reviewDue} the person's
     * confirmed-PEP hits no longer hold the gate; after it they do again. Fails if the person has no
     * confirmed-PEP hit. @return the number of hits covered.
     */
    int recordPepEddApproval(UUID naturalPersonId, UUID eddApprovalId, Instant reviewDue,
                             UUID actorId, String actorRole, UUID approverId);

    /**
     * True while the entity's gate answer rests on a last good result because the latest run failed
     * (provider outage inside the grace window). Meant for a visible degraded-state indicator; the
     * subject is not "clear" in the ordinary sense and the answer expires with the grace window.
     */
    boolean isRelyingOnStaleResult(UUID entityId);
}
