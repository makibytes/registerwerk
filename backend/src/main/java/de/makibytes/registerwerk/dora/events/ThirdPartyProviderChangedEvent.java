package de.makibytes.registerwerk.dora.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * DORA Register of Information (RoI) — a critical/important ICT third-party provider was
 * created or updated. Previously the RoI had no write path at all outside demo data, so this
 * event never fired in a real deployment.
 */
public record ThirdPartyProviderChangedEvent(
        UUID providerId, String changeType, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "DORA_THIRD_PARTY_PROVIDER_" + changeType; }
    public String subjectType() { return "ThirdPartyProvider"; }
    public UUID   subjectId()   { return providerId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
