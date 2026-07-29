package de.makibytes.registerwerk.support.web.dto;

import de.makibytes.registerwerk.support.api.SupportTicketMessage;

import java.time.Instant;
import java.util.UUID;

public record SupportTicketMessageResponse(UUID id, UUID authorId, boolean authorIsOperator, String body, Instant createdAt) {

    public static SupportTicketMessageResponse of(SupportTicketMessage m) {
        return new SupportTicketMessageResponse(m.getId(), m.getAuthorId(), m.isAuthorIsOperator(), m.getBody(), m.getCreatedAt());
    }
}
