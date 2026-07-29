package de.makibytes.registerwerk.orgidentity.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record TrustedIssuerChangedEvent(
        UUID issuerId, UUID actorId, String actorRole, UUID dualControlApproverId, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "ECOSYSTEM_TRUSTED_ISSUER_CHANGED"; }
    public String subjectType() { return "EcosystemTrustedIssuer"; }
    public UUID   subjectId()   { return issuerId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
