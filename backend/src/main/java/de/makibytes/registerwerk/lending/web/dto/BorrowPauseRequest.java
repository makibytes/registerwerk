package de.makibytes.registerwerk.lending.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BorrowPauseRequest(@NotNull Boolean paused, @NotBlank String reason) {}
