package de.makibytes.registerwerk.support.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** An operator posted a customer-visible message on a ticket (7A-09). The body is not copied into the audit payload. */
public record SupportTicketMessageAddedEvent(UUID ticketId, UUID actorId, String actorRole, int bodyLength)
        implements AuditableEvent {
    public String eventType()   { return "SUPPORT_TICKET_OPERATOR_MESSAGE"; }
    public String subjectType() { return "SupportTicket"; }
    public UUID   subjectId()   { return ticketId; }
    public Map<String, Object> payload() {
        return Map.of("authorRole", actorRole == null ? "" : actorRole, "bodyLength", bodyLength);
    }
}
