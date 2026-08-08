package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.screening.api.NaturalPersonScreeningSubjectResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class KycNaturalPersonScreeningSubjectResolver implements NaturalPersonScreeningSubjectResolver {

    private final NaturalPersonRepository naturalPersonRepository;

    KycNaturalPersonScreeningSubjectResolver(NaturalPersonRepository naturalPersonRepository) {
        this.naturalPersonRepository = naturalPersonRepository;
    }

    @Override
    public Optional<NaturalPersonScreeningSubject> findById(UUID naturalPersonId) {
        return naturalPersonRepository.findById(naturalPersonId).map(person ->
                new NaturalPersonScreeningSubject(
                        (person.getGivenName() + " " + person.getFamilyName()).trim(),
                        person.getCountry()));
    }
}
