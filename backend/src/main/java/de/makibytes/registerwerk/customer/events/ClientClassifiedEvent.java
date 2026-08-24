package de.makibytes.registerwerk.customer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record ClientClassifiedEvent(UUID entityId, UUID actorId, String actorRole, String clientCategory)
        implements AuditableEvent {
    public String eventType()   { return "CLIENT_CLASSIFIED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() { return Map.of("clientCategory", clientCategory); }
}
