package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.BeneficialOwnerRepository;
import de.makibytes.registerwerk.kyc.api.NaturalPerson;
import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.kyc.events.BeneficialOwnerAddedEvent;
import de.makibytes.registerwerk.kyc.events.BeneficialOwnerCeasedEvent;
import de.makibytes.registerwerk.kyc.web.dto.BeneficialOwnerRequest;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registers and manages beneficial owners (GwG §3, AMLR Art. 42) — the UBO data path that,
 * until this class existed, had a fully-modeled {@link NaturalPerson}/{@link BeneficialOwner}
 * schema with zero code path ever writing to it. That silently neutered {@code
 * ScreeningGateImpl.hasUnresolvedBeneficialOwnerHit}'s join against {@code beneficial_owner}
 * (always empty ⇒ always {@code false}), making the UBO sanctions gate inside {@code
 * KycService.approveKyc}/{@code approveKycForJurisdiction} a silent no-op in practice, despite
 * reading as fully implemented.
 */
@Service
@Transactional
public class BeneficialOwnerService {

    private static final Logger log = LoggerFactory.getLogger(BeneficialOwnerService.class);

    private final BeneficialOwnerRepository beneficialOwnerRepository;
    private final NaturalPersonRepository naturalPersonRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ScreeningGate screeningGate;

    BeneficialOwnerService(
            BeneficialOwnerRepository beneficialOwnerRepository,
            NaturalPersonRepository naturalPersonRepository,
            LegalEntityRepository legalEntityRepository,
            ApplicationEventPublisher eventPublisher,
            ScreeningGate screeningGate) {
        this.beneficialOwnerRepository = beneficialOwnerRepository;
        this.naturalPersonRepository = naturalPersonRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.eventPublisher = eventPublisher;
        this.screeningGate = screeningGate;
    }

    /**
     * Registers a new natural person as a beneficial owner of {@code entityId} and immediately
     * triggers a sanctions/PEP screening run for them (GwG §11) — the "beneficial-owner add"
     * trigger point {@code docs/compliance/sanctions-screening.md} has always documented but
     * that, before this class, had no beneficial-owner data to trigger on at all.
     */
    public BeneficialOwner addBeneficialOwner(
            UUID entityId,
            BeneficialOwnerRequest.NaturalPersonInput personInput,
            java.math.BigDecimal ownershipPct,
            BeneficialOwner.ControlType controlType,
            String source,
            UUID actorId,
            String actorRole) {

        legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));

        NaturalPerson person = new NaturalPerson();
        person.setGivenName(personInput.givenName());
        person.setFamilyName(personInput.familyName());
        person.setDateOfBirth(personInput.dateOfBirth());
        person.setNationality(personInput.nationality());
        person.setCountryOfResidence(personInput.countryOfResidence());
        person.setTaxId(personInput.taxId());
        person.setTaxIdCountry(personInput.taxIdCountry());
        person.setAddressLine1(personInput.addressLine1());
        person.setAddressLine2(personInput.addressLine2());
        person.setCity(personInput.city());
        person.setPostalCode(personInput.postalCode());
        person.setCountry(personInput.country());
        NaturalPerson savedPerson = naturalPersonRepository.save(person);

        BeneficialOwner beneficialOwner = new BeneficialOwner();
        beneficialOwner.setEntityId(entityId);
        beneficialOwner.setNaturalPersonId(savedPerson.getId());
        beneficialOwner.setOwnershipPct(ownershipPct);
        beneficialOwner.setControlType(controlType);
        beneficialOwner.setSource(source);
        BeneficialOwner saved = beneficialOwnerRepository.save(beneficialOwner);

        eventPublisher.publishEvent(new BeneficialOwnerAddedEvent(saved.getId(), actorId, actorRole, Map.of(
                "entityId", entityId.toString(),
                "naturalPersonId", savedPerson.getId().toString(),
                "controlType", controlType.name()
        )));

        String fullName = (savedPerson.getGivenName() + " " + savedPerson.getFamilyName()).trim();
        try {
            screeningGate.screenNaturalPerson(savedPerson.getId(), fullName, savedPerson.getCountry(),
                    ScreeningTrigger.BENEFICIAL_OWNER_ADD);
        } catch (Exception e) {
            // A screening-provider outage must not block registering the UBO record itself —
            // ScreeningGateImpl.hasUnresolvedBeneficialOwnerHit fails closed at KYC-approval
            // time regardless (no run at all ⇒ blocks), so this UBO cannot be used to approve
            // KYC until a screening run does complete, one way or another.
            log.error("Screening trigger failed for newly added beneficial owner naturalPerson={}: {}",
                    savedPerson.getId(), e.getMessage(), e);
        }

        log.info("Beneficial owner added: id={} entity={} naturalPerson={}", saved.getId(), entityId, savedPerson.getId());
        return saved;
    }

    /** Marks a beneficial-owner link as ceased (ownership/control ended) — soft, never deleted. */
    public BeneficialOwner ceaseBeneficialOwner(UUID beneficialOwnerId, UUID actorId, String actorRole) {
        BeneficialOwner beneficialOwner = beneficialOwnerRepository.findById(beneficialOwnerId)
                .orElseThrow(() -> new EntityNotFoundException("BeneficialOwner", beneficialOwnerId));
        if (beneficialOwner.getCeasedAt() != null) {
            throw new InvalidStateTransitionException("Beneficial owner link is already ceased");
        }
        beneficialOwner.setCeasedAt(Instant.now());
        BeneficialOwner saved = beneficialOwnerRepository.save(beneficialOwner);

        eventPublisher.publishEvent(new BeneficialOwnerCeasedEvent(saved.getId(), actorId, actorRole, Map.of(
                "entityId", saved.getEntityId().toString()
        )));
        log.info("Beneficial owner ceased: id={} entity={}", saved.getId(), saved.getEntityId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<BeneficialOwner> listActive(UUID entityId) {
        return beneficialOwnerRepository.findByEntityIdAndCeasedAtIsNull(entityId);
    }

    @Transactional(readOnly = true)
    public NaturalPerson requireNaturalPerson(UUID naturalPersonId) {
        return naturalPersonRepository.findById(naturalPersonId)
                .orElseThrow(() -> new EntityNotFoundException("NaturalPerson", naturalPersonId));
    }
}
