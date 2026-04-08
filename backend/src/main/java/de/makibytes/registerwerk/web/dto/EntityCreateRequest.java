package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.EntityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request payload for creating a new legal entity.
 */
public record EntityCreateRequest(
    @NotNull EntityType type,
    @NotBlank String currentName,
    String registrationNumber,
    String registrationCountry,
    LocalDate incorporationDate,
    String leiCode
) {}
