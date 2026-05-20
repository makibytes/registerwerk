package de.makibytes.registerwerk.screening.api;

import java.util.List;
import java.util.UUID;

/**
 * Port for pluggable sanctions/PEP screening providers.
 * Adapters: OpenSanctionsAdapter (free), RefinitivWorldCheckAdapter (commercial).
 */
public interface SanctionsScreeningPort {

    String providerName();

    /**
     * Screen a legal entity. Returns a list of hits (empty = clear).
     * Implementations must be idempotent and circuit-breakered.
     */
    List<ScreeningHitDto> screenEntity(ScreeningSubjectDto subject);

    record ScreeningSubjectDto(
            UUID subjectId,
            String subjectType,   // "LEGAL_ENTITY" | "NATURAL_PERSON"
            String name,
            String countryCode,
            String lei,
            String registrationNumber
    ) {}

    record ScreeningHitDto(
            String listSource,
            String matchedField,
            String matchedValue,
            double matchScore,
            String details
    ) {}
}
