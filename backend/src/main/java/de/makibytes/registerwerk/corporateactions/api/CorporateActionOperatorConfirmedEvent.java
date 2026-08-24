package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Published when an operator confirms a corporate action's settlement readiness — the second of
 * the two required parties, after the issuer's own attestation ({@link CorporateActionIssuerAttestedEvent}
 * or its override). Renamed from {@code CorporateActionDualControlApprovedEvent}: this module's
 * original "2×-operator dual control" design (two REGISTRY_ADMIN staff approving each other) has
 * been replaced by this cross-party issuer+operator model — two parties from two organizations,
 * not two colleagues from the same one. Verified zero external consumers of the old event-type
 * string before renaming.
 */
public record CorporateActionOperatorConfirmedEvent(
        UUID corporateActionId, UUID actorId, String actorRole, UUID issuerAttestedBy
) implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_OPERATOR_CONFIRMED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public Map<String, Object> payload() {
        return Map.of("issuerAttestedBy", issuerAttestedBy != null ? issuerAttestedBy.toString() : "");
    }
}
