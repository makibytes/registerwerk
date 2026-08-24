package de.makibytes.registerwerk.finality.web.dto;

import jakarta.validation.constraints.NotBlank;

/** @param reason mandatory — see {@code FinalityJournalAdminService.acknowledge}'s javadoc. */
public record AcknowledgeRequest(@NotBlank String reason) {}
