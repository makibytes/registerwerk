package de.makibytes.registerwerk.screening.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Port for pluggable sanctions/PEP screening providers.
 * The only adapter in this repository is {@code OpenSanctionsAdapter}; a licensed provider would be
 * a second implementation of this port (provider choice is a parked decision, T6-04).
 */
public interface SanctionsScreeningPort {

    String providerName();

    /**
     * Screen a subject. Returns a list of hits (empty = clear).
     * Implementations must be idempotent and circuit-breakered.
     */
    List<ScreeningHitDto> screenEntity(ScreeningSubjectDto subject);

    /**
     * Same as {@link #screenEntity} plus provenance of the answer (dataset version, effective match
     * threshold) so it can be stored on the run. Adapters that know neither keep this default.
     */
    default ScreeningResult screen(ScreeningSubjectDto subject) {
        return new ScreeningResult(screenEntity(subject), null, null);
    }

    record ScreeningSubjectDto(
            UUID subjectId,
            String subjectType,   // "LEGAL_ENTITY" | "NATURAL_PERSON"
            String name,
            String countryCode,
            String lei,
            String registrationNumber,
            /** Natural persons only; null when unknown. Sent to the provider to cut namesake false positives. */
            LocalDate dateOfBirth,
            /** Natural persons only, ISO 3166-1 alpha-2; null when unknown. */
            String nationality
    ) {
        public ScreeningSubjectDto(UUID subjectId, String subjectType, String name, String countryCode,
                                   String lei, String registrationNumber) {
            this(subjectId, subjectType, name, countryCode, lei, registrationNumber, null, null);
        }
    }

    record ScreeningHitDto(
            String listSource,
            String matchedField,
            String matchedValue,
            double matchScore,
            String details,
            /** "SANCTIONS" | "PEP" | "ADVERSE_MEDIA"; null if the provider can't tell — treated as SANCTIONS. */
            String category,
            /** Provider's stable id of the matched record (basis of the hit fingerprint); null if unknown. */
            String externalId
    ) {
        public ScreeningHitDto(String listSource, String matchedField, String matchedValue, double matchScore,
                               String details, String category) {
            this(listSource, matchedField, matchedValue, matchScore, details, category, null);
        }
    }

    /**
     * @param dataVersion   dataset/index version string reported by the provider, null if it reports none
     * @param thresholdUsed effective match threshold applied by the adapter, null if not applicable
     */
    record ScreeningResult(List<ScreeningHitDto> hits, String dataVersion, BigDecimal thresholdUsed) {}
}
