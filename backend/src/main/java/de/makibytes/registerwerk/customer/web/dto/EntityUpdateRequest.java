package de.makibytes.registerwerk.customer.web.dto;

import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Request payload for partial update of a legal entity. All fields are optional.
 */
public record EntityUpdateRequest(
    @Size(min = 1, max = 500) String currentName,
    @Pattern(regexp = "[A-Z0-9]{20}", message = "must be a 20-character LEI") String leiCode,
    @Size(max = 100) String registrationNumber,
    @Pattern(regexp = "[A-Z]{2}", message = "must be an ISO-3166 alpha-2 country code") String registrationCountry,
    @PastOrPresent LocalDate incorporationDate
) {}
