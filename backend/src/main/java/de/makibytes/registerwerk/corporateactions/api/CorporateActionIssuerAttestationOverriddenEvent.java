package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Published when an operator overrides the issuer-attestation requirement for a corporate
 * action's settlement — the escape hatch for an issuer who never logs in to attest themselves.
 * Deliberately a distinct event type from {@link CorporateActionIssuerAttestedEvent} (not merely
 * a flag on it): this is an exception to the normal cross-party control, and must always be
 * separately visible and countable in the audit log, never silently indistinguishable from a
 * genuine issuer attestation.
 */
public record CorporateActionIssuerAttestationOverriddenEvent(
        UUID corporateActionId, UUID actorId, String actorRole, String reason
) implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_ISSUER_ATTESTATION_OVERRIDDEN"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public Map<String, Object> payload() {
        return Map.of("reason", reason != null ? reason : "");
    }
}
