package de.makibytes.registerwerk.customer.web.dto;

import java.time.LocalDate;

/**
 * Request payload for partial update of a legal entity. All fields are optional.
 */
public record EntityUpdateRequest(
    String currentName,
    String leiCode,
    String registrationNumber,
    String registrationCountry,
    LocalDate incorporationDate
) {}
