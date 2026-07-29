package de.makibytes.registerwerk.support.web.dto;

import de.makibytes.registerwerk.support.api.SupportTicket;

import java.time.Instant;
import java.util.UUID;

public record SupportTicketResponse(
        UUID id,
        UUID entityId,
        UUID createdBy,
        String subject,
        String description,
        SupportTicket.Category category,
        SupportTicket.Priority priority,
        SupportTicket.Status status,
        UUID assignedTo,
        String resolutionNotes,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt,
        Instant closedAt) {

    public static SupportTicketResponse of(SupportTicket t) {
        return new SupportTicketResponse(
                t.getId(), t.getEntityId(), t.getCreatedBy(), t.getSubject(), t.getDescription(),
                t.getCategory(), t.getPriority(), t.getStatus(), t.getAssignedTo(), t.getResolutionNotes(),
                t.getCreatedAt(), t.getUpdatedAt(), t.getResolvedAt(), t.getClosedAt());
    }
}
