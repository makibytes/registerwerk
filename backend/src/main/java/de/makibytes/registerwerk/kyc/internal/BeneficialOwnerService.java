package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.EntityTask;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.BeneficialOwnerRepository;
import de.makibytes.registerwerk.kyc.api.EddApproval;
import de.makibytes.registerwerk.kyc.api.EddApprovalRepository;
import de.makibytes.registerwerk.kyc.api.KycDocument;
import de.makibytes.registerwerk.kyc.api.KycDocumentRepository;
import de.makibytes.registerwerk.kyc.api.NaturalPerson;
import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.kyc.events.BeneficialOwnerAddedEvent;
import de.makibytes.registerwerk.kyc.events.BeneficialOwnerCeasedEvent;
import de.makibytes.registerwerk.kyc.events.BeneficialOwnerVerifiedEvent;
import de.makibytes.registerwerk.kyc.events.EddApprovedEvent;
import de.makibytes.registerwerk.shared.ComplianceGateException;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
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
    private final EntityTaskPort entityTaskPort;
    private final KycDocumentRepository kycDocumentRepository;
    private final EddApprovalRepository eddApprovalRepository;
    private final KycEvidenceService evidenceService;

    /** EDD approvals lapse after at most this many months (parked decision T6-02 interim). */
    static final int EDD_MAX_MONTHS = 6;

    BeneficialOwnerService(
            BeneficialOwnerRepository beneficialOwnerRepository,
            NaturalPersonRepository naturalPersonRepository,
            LegalEntityRepository legalEntityRepository,
            ApplicationEventPublisher eventPublisher,
            ScreeningGate screeningGate,
            EntityTaskPort entityTaskPort,
            KycDocumentRepository kycDocumentRepository,
            EddApprovalRepository eddApprovalRepository,
            KycEvidenceService evidenceService) {
        this.beneficialOwnerRepository = beneficialOwnerRepository;
        this.naturalPersonRepository = naturalPersonRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.eventPublisher = eventPublisher;
        this.screeningGate = screeningGate;
        this.entityTaskPort = entityTaskPort;
        this.kycDocumentRepository = kycDocumentRepository;
        this.eddApprovalRepository = eddApprovalRepository;
        this.evidenceService = evidenceService;
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
            String fallbackReason,
            UUID actorId,
            String actorRole) {

        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
        validateOwnership(entityId, ownershipPct, controlType, fallbackReason);

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
        if (controlType == BeneficialOwner.ControlType.SENIOR_MANAGING_OFFICIAL) {
            beneficialOwner.setFallbackReason(fallbackReason.trim());
        }
        BeneficialOwner saved = beneficialOwnerRepository.save(beneficialOwner);

        Map<String, Object> addedDetails = new LinkedHashMap<>();
        addedDetails.put("entityId", entityId.toString());
        addedDetails.put("naturalPersonId", savedPerson.getId().toString());
        addedDetails.put("controlType", controlType.name());
        if (ownershipPct != null) addedDetails.put("ownershipPct", ownershipPct.toPlainString());
        if (fallbackReason != null && controlType == BeneficialOwner.ControlType.SENIOR_MANAGING_OFFICIAL) {
            addedDetails.put("fallbackReason", fallbackReason.trim());
        }
        eventPublisher.publishEvent(new BeneficialOwnerAddedEvent(saved.getId(), actorId, actorRole, addedDetails));
        requestReviewIfApproved(entity, "BO_ADDED:" + saved.getId(),
                "A beneficial owner was added to an APPROVED entity; review the KYC file. The KYC status was not changed automatically.",
                actorId);

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

    /**
     * Marks a beneficial-owner link as ceased (ownership/control ended) - soft, never deleted (6-16).
     * Needs a reason and is refused while the person's screening is unresolved (open hit, never screened, error
     * beyond grace): the hit has to be resolved through the acceptance path first, so one actor cannot make a
     * sanctioned person disappear from the gate. The controller adds step-up and a second approver. Ceasing on
     * an APPROVED entity opens a KYC_REVIEW_REQUIRED task; the KYC status is not changed automatically (T6-14).
     */
    public BeneficialOwner ceaseBeneficialOwner(UUID entityId, UUID beneficialOwnerId, String reason, UUID documentId,
                                                 UUID actorId, String actorRole, UUID approverId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is mandatory when ceasing a beneficial owner.");
        }
        BeneficialOwner beneficialOwner = beneficialOwnerRepository.findByIdAndEntityId(beneficialOwnerId, entityId)
                .orElseThrow(() -> new EntityNotFoundException("BeneficialOwner", beneficialOwnerId));
        if (beneficialOwner.getCeasedAt() != null) {
            throw new InvalidStateTransitionException("Beneficial owner link is already ceased");
        }
        if (documentId != null) {
            requireEntityDocument(entityId, documentId);
        }
        if (screeningGate.hasUnresolvedHitForPerson(beneficialOwner.getNaturalPersonId())) {
            throw new ComplianceGateException(
                    "Cannot cease this beneficial owner: the person's screening is not CLEAR/ACCEPTED (open hit, pending, "
                    + "failed or never screened). Resolve the screening condition first (GwG §10/§11).");
        }
        beneficialOwner.setCeasedAt(Instant.now());
        beneficialOwner.setCeasedBy(actorId);
        beneficialOwner.setCeaseReason(reason.trim());
        beneficialOwner.setCeaseDocumentId(documentId);
        BeneficialOwner saved = beneficialOwnerRepository.save(beneficialOwner);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("entityId", saved.getEntityId().toString());
        details.put("reason", reason.trim());
        details.put("screeningState", "CLEAR_OR_ACCEPTED");
        if (documentId != null) details.put("documentId", documentId.toString());
        if (approverId != null) details.put("dualControlApproverId", approverId.toString());
        eventPublisher.publishEvent(new BeneficialOwnerCeasedEvent(saved.getId(), actorId, actorRole, details));
        legalEntityRepository.findById(entityId).ifPresent(entity -> requestReviewIfApproved(entity,
                "BO_CEASED:" + saved.getId(),
                "A beneficial owner was ceased on an APPROVED entity; review the KYC file. The KYC status was not changed automatically.",
                actorId));
        log.info("Beneficial owner ceased: id={} entity={}", saved.getId(), saved.getEntityId());
        return saved;
    }

    /** Records that the owner's identity was verified against a stored, unexpired evidence document (6-17). */
    public BeneficialOwner verifyBeneficialOwner(UUID entityId, UUID beneficialOwnerId, UUID documentId,
                                                  UUID actorId, String actorRole) {
        BeneficialOwner beneficialOwner = beneficialOwnerRepository.findByIdAndEntityId(beneficialOwnerId, entityId)
                .orElseThrow(() -> new EntityNotFoundException("BeneficialOwner", beneficialOwnerId));
        if (beneficialOwner.getCeasedAt() != null) {
            throw new InvalidStateTransitionException("Beneficial owner link is ceased");
        }
        KycDocument doc = requireEntityDocument(entityId, documentId);
        if (doc.getExpiresAt() != null && doc.getExpiresAt().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("The evidence document has expired.");
        }
        beneficialOwner.setVerifiedBy(actorId);
        beneficialOwner.setVerifiedAt(Instant.now());
        beneficialOwner.setVerificationDocumentId(documentId);
        BeneficialOwner saved = beneficialOwnerRepository.save(beneficialOwner);
        eventPublisher.publishEvent(new BeneficialOwnerVerifiedEvent(saved.getId(), actorId, actorRole, Map.of(
                "entityId", entityId.toString(),
                "documentId", documentId.toString(),
                "documentType", doc.getDocumentType().name())));
        return saved;
    }

    /**
     * Four-eyes EDD approval for a beneficial owner who is a confirmed PEP (6-17, parked decision T6-02): writes
     * the approval row (review due capped at {@value #EDD_MAX_MONTHS} months) and tells the screening gate, which
     * lets the confirmed-PEP hit pass until the review date.
     */
    public EddApproval approveEdd(UUID entityId, UUID beneficialOwnerId, String note, LocalDate reviewDueDate,
                                  UUID actorId, String actorRole, UUID approverId) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("A note is mandatory for an EDD approval.");
        }
        if (approverId == null || approverId.equals(actorId)) {
            throw new ComplianceGateException("EDD approval needs a second approver different from the approving user.");
        }
        BeneficialOwner bo = beneficialOwnerRepository.findByIdAndEntityId(beneficialOwnerId, entityId)
                .orElseThrow(() -> new EntityNotFoundException("BeneficialOwner", beneficialOwnerId));
        if (bo.getCeasedAt() != null) {
            throw new InvalidStateTransitionException("Beneficial owner link is ceased");
        }
        NaturalPerson person = requireNaturalPerson(bo.getNaturalPersonId());
        if (person.getPepStatus() != NaturalPerson.PepStatus.CONFIRMED_PEP) {
            throw new ComplianceGateException("EDD approval applies only to a confirmed PEP; confirm the screening "
                    + "PEP hit first (resolution CONFIRM_PEP).");
        }
        Instant cap = ZonedDateTime.now(ZoneOffset.UTC).plusMonths(EDD_MAX_MONTHS).toInstant();
        Instant reviewDue = cap;
        if (reviewDueDate != null) {
            reviewDue = reviewDueDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (!reviewDue.isAfter(Instant.now())) {
                throw new IllegalArgumentException("reviewDueDate must be in the future.");
            }
            if (reviewDue.isAfter(cap)) {
                throw new IllegalArgumentException("reviewDueDate exceeds the maximum EDD review interval of "
                        + EDD_MAX_MONTHS + " months.");
            }
        }
        EddApproval edd = new EddApproval();
        edd.setNaturalPersonId(person.getId());
        edd.setApprovedBy(actorId);
        edd.setSecondApproverId(approverId);
        edd.setNote(note.trim());
        edd.setReviewDue(reviewDue);
        EddApproval saved = eddApprovalRepository.save(edd);
        int covered = screeningGate.recordPepEddApproval(person.getId(), saved.getId(), reviewDue, actorId, actorRole, approverId);
        eventPublisher.publishEvent(new EddApprovedEvent(person.getId(), actorId, actorRole, Map.of(
                "entityId", entityId.toString(),
                "eddApprovalId", saved.getId().toString(),
                "reviewDue", reviewDue.toString(),
                "note", note.trim(),
                "dualControlApproverId", approverId.toString(),
                "hitsCovered", covered)));
        return saved;
    }

    @Transactional(readOnly = true)
    public KycEvidenceService.OwnershipSummary ownershipSummary(UUID entityId) {
        return evidenceService.ownershipSummary(entityId);
    }

    private void validateOwnership(UUID entityId, BigDecimal pct, BeneficialOwner.ControlType controlType, String fallbackReason) {
        if (pct != null) {
            if (pct.signum() <= 0 || pct.compareTo(KycEvidenceService.HUNDRED) > 0) {
                throw new IllegalArgumentException("ownershipPct must be greater than 0 and at most 100.");
            }
        }
        if (controlType == BeneficialOwner.ControlType.SENIOR_MANAGING_OFFICIAL) {
            if (fallbackReason == null || fallbackReason.isBlank()) {
                throw new IllegalArgumentException("A senior managing official fallback needs a documented reason "
                        + "(why no beneficial owner could be identified).");
            }
            if (pct != null) {
                throw new IllegalArgumentException("A senior managing official has no ownership percentage.");
            }
            return;
        }
        if (pct != null) {
            BigDecimal current = beneficialOwnerRepository.findByEntityIdAndCeasedAtIsNull(entityId).stream()
                    .filter(bo -> bo.getControlType() != BeneficialOwner.ControlType.SENIOR_MANAGING_OFFICIAL)
                    .map(BeneficialOwner::getOwnershipPct)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (current.add(pct).compareTo(KycEvidenceService.HUNDRED) > 0) {
                throw new IllegalArgumentException("The recorded ownership would exceed 100 % (already "
                        + current.toPlainString() + " %).");
            }
        }
    }

    private KycDocument requireEntityDocument(UUID entityId, UUID documentId) {
        return kycDocumentRepository.findByIdAndLegalEntityIdAndDeletedAtIsNull(documentId, entityId)
                .orElseThrow(() -> new EntityNotFoundException("KycDocument", documentId));
    }

    private void requestReviewIfApproved(LegalEntity entity, String ref, String detail, UUID actorId) {
        if (entity.getKycStatus() == KycStatus.APPROVED) {
            entityTaskPort.open(entity.getId(), EntityTask.KYC_REVIEW_REQUIRED, ref, detail, actorId);
        }
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
