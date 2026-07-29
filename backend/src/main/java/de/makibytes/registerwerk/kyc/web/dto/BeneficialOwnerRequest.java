package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Registers a natural person as a beneficial owner of a legal entity (GwG §3, AMLR Art. 42).
 * The natural person is created fresh — this endpoint does not currently support linking an
 * already-registered {@code NaturalPerson} to a second entity.
 */
public record BeneficialOwnerRequest(
        @Valid @NotNull NaturalPersonInput person,
        BigDecimal ownershipPct,
        @NotNull BeneficialOwner.ControlType controlType,
        String source
) {
    public record NaturalPersonInput(
            @NotBlank String givenName,
            @NotBlank String familyName,
            LocalDate dateOfBirth,
            String nationality,
            String countryOfResidence,
            String taxId,
            String taxIdCountry,
            String addressLine1,
            String addressLine2,
            String city,
            String postalCode,
            String country
    ) {}
}
