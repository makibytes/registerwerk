package de.makibytes.registerwerk.support.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AddMessageRequest(@NotBlank String body) {}
