package de.makibytes.registerwerk.support.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ResolveTicketRequest(@NotBlank String resolutionNotes) {}
