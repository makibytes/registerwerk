package de.makibytes.registerwerk.accessreview.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReopenItemRequest(@NotBlank @Size(min = 10, max = 1000) String reason) {}
