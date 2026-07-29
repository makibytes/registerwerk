package de.makibytes.registerwerk.support.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record SupportTicketStatusChangedEvent(UUID ticketId, UUID actorId, String actorRole,
                                               String fromStatus, String toStatus)
        implements AuditableEvent {
    public String eventType()   { return "SUPPORT_TICKET_STATUS_CHANGED"; }
    public String subjectType() { return "SupportTicket"; }
    public UUID   subjectId()   { return ticketId; }
    public Map<String, Object> payload() {
        return Map.of("fromStatus", fromStatus, "toStatus", toStatus);
    }
}
