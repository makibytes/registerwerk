package de.makibytes.registerwerk.customer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record CompanyExternalIdUpsertedEvent(UUID entityId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "COMPANY_EXTERNAL_ID_UPSERTED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
