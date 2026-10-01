package de.makibytes.registerwerk.screening.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Resolves canonical KYC data without making the screening module depend on KYC internals. */
public interface NaturalPersonScreeningSubjectResolver {

    Optional<NaturalPersonScreeningSubject> findById(UUID naturalPersonId);

    /** @param dateOfBirth and nationality may be null; they are sent to the provider to cut namesake hits */
    record NaturalPersonScreeningSubject(String fullName, String countryCode, LocalDate dateOfBirth,
                                         String nationality) {
        public NaturalPersonScreeningSubject(String fullName, String countryCode) {
            this(fullName, countryCode, null, null);
        }
    }
}
