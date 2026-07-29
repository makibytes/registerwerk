package de.makibytes.registerwerk.support.web.dto;

import de.makibytes.registerwerk.support.api.SupportTicket;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateSupportTicketRequest(
        @NotBlank String subject,
        @NotBlank String description,
        @NotNull SupportTicket.Category category,
        SupportTicket.Priority priority) {
}
