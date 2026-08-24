package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Published when an issuer attests that the underlying obligation/cash-leg for a corporate
 * action's settlement is ready — the first of the two required parties before an operator can
 * confirm settlement. See {@link CorporateActionIssuerAttestationOverriddenEvent} for the
 * distinct, separately-audited operator-override path used when an issuer never attests.
 */
public record CorporateActionIssuerAttestedEvent(
        UUID corporateActionId, UUID actorId, String actorRole, String attestationReference
) implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_ISSUER_ATTESTED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public Map<String, Object> payload() {
        return Map.of("attestationReference", attestationReference != null ? attestationReference : "");
    }
}
