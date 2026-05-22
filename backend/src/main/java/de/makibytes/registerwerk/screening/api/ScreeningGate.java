package de.makibytes.registerwerk.screening.api;

import java.util.UUID;

/**
 * Public API façade for the screening module.
 * Used by other modules (kyc, onboarding) to check screening status without
 * crossing into screening/internal/.
 */
public interface ScreeningGate {

    /**
     * Returns true if the entity has at least one unresolved (accepted=null) screening HIT.
     * A compliance officer must review and dismiss the hit before KYC can be approved.
     */
    boolean hasUnresolvedHit(UUID entityId);

    /**
     * Returns true if any beneficial owner / natural person linked to the entity
     * has an unresolved screening HIT.
     */
    boolean hasUnresolvedBeneficialOwnerHit(UUID entityId);
}
