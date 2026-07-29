package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.NaturalPerson;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BeneficialOwnerResponse(
        UUID id,
        UUID entityId,
        UUID naturalPersonId,
        String givenName,
        String familyName,
        String country,
        NaturalPerson.PepStatus pepStatus,
        BigDecimal ownershipPct,
        BeneficialOwner.ControlType controlType,
        Instant registeredAt,
        Instant ceasedAt
) {
    public static BeneficialOwnerResponse from(BeneficialOwner bo, NaturalPerson person) {
        return new BeneficialOwnerResponse(
                bo.getId(), bo.getEntityId(), bo.getNaturalPersonId(),
                person.getGivenName(), person.getFamilyName(), person.getCountry(), person.getPepStatus(),
                bo.getOwnershipPct(), bo.getControlType(), bo.getRegisteredAt(), bo.getCeasedAt());
    }
}
