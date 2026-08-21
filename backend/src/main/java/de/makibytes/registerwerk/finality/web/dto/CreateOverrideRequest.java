package de.makibytes.registerwerk.finality.web.dto;

import de.makibytes.registerwerk.finality.api.FinalityLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** @param operation must name a real {@code GatedOperation} constant — validated by the
 *                    controller (a bad value maps to 400 via {@code IllegalArgumentException}). */
public record CreateOverrideRequest(
        @NotBlank String operation,
        @NotNull FinalityLevel requiredLevel,
        @NotBlank String reason) {
}
