package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record TraderSettingsUpdatedEvent(UUID entityId, UUID actorId, String actorRole) implements AuditableEvent {
    public String eventType()   { return "TRADER_SETTINGS_UPDATED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() { return Map.of(); }
}
