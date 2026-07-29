package de.makibytes.registerwerk.support.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record SupportTicketCreatedEvent(UUID ticketId, UUID entityId, UUID actorId, String actorRole,
                                         String category, String priority)
        implements AuditableEvent {
    public String eventType()   { return "SUPPORT_TICKET_CREATED"; }
    public String subjectType() { return "SupportTicket"; }
    public UUID   subjectId()   { return ticketId; }
    public Map<String, Object> payload() {
        return Map.of("entityId", entityId, "category", category, "priority", priority);
    }
}
