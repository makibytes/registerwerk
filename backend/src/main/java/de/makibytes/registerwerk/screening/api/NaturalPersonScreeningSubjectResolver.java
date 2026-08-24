package de.makibytes.registerwerk.screening.api;

import java.util.Optional;
import java.util.UUID;

/** Resolves canonical KYC data without making the screening module depend on KYC internals. */
public interface NaturalPersonScreeningSubjectResolver {

    Optional<NaturalPersonScreeningSubject> findById(UUID naturalPersonId);

    record NaturalPersonScreeningSubject(String fullName, String countryCode) {}
}
