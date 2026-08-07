package de.makibytes.registerwerk.accessreview.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RecordDecisionRequest(@NotBlank String decision, String notes) {}
