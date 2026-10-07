package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.BeneficialOwnerRepository;
import de.makibytes.registerwerk.kyc.api.EddApproval;
import de.makibytes.registerwerk.kyc.api.EddApprovalRepository;
import de.makibytes.registerwerk.kyc.api.KycApprovalRecord;
import de.makibytes.registerwerk.kyc.api.KycApprovalRecordRepository;
import de.makibytes.registerwerk.kyc.api.KycComplianceService;
import de.makibytes.registerwerk.kyc.api.KycDocument;
import de.makibytes.registerwerk.kyc.api.KycDocumentRepository;
import de.makibytes.registerwerk.kyc.api.KycJurisdictionApproval;
import de.makibytes.registerwerk.kyc.api.NaturalPerson;
import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The compliance officer's working view (T8-03): the KYC queue and a scoped, read-only review of one entity.
 * It carries what a KYC / beneficial-owner / EDD decision rests on and nothing else - no contact data, wallets,
 * balances or administrative fields - so granting the officer this read does not widen access to the entity
 * itself. The gap and ownership computations are the ones the approval itself runs ({@link KycEvidenceService}).
 */
@Service
@Transactional(readOnly = true)
public class KycReviewService {

    /** KYC expiring within this many days is queued (same horizon as the nightly {@code KycMonitoringJob}). */
    static final int EXPIRY_WARNING_DAYS = 30;

    public record QueueItem(UUID entityId, String entityName, Jurisdiction homeJurisdiction, KycStatus kycStatus,
                            LocalDate kycExpiryDate, List<String> reasons) {}

    public record OwnerView(BeneficialOwner owner, NaturalPerson person, boolean screeningUnresolved,
                            Optional<Instant> eddReviewDue) {}

    public record ScreeningState(boolean entityHitUnresolved, boolean beneficialOwnerHitUnresolved,
                                 boolean relyingOnStaleResult) {}

    public record Review(LegalEntity entity, Jurisdiction homeJurisdiction, List<KycDocument> documents,
                         KycComplianceService.ComplianceResult checklist,
                         List<KycJurisdictionApproval> jurisdictionApprovals,
                         KycEvidenceService.OwnershipSummary ownership, List<OwnerView> owners,
                         ScreeningState screening, List<String> gaps, List<KycApprovalRecord> decisions) {}

    private final LegalEntityRepository legalEntityRepository;
    private final BeneficialOwnerRepository beneficialOwnerRepository;
    private final NaturalPersonRepository naturalPersonRepository;
    private final EddApprovalRepository eddApprovalRepository;
    private final KycDocumentRepository kycDocumentRepository;
    private final KycApprovalRecordRepository approvalRecordRepository;
    private final KycEvidenceService evidenceService;
    private final KycService kycService;
    private final ScreeningGate screeningGate;

    public KycReviewService(
            LegalEntityRepository legalEntityRepository,
            BeneficialOwnerRepository beneficialOwnerRepository,
            NaturalPersonRepository naturalPersonRepository,
            EddApprovalRepository eddApprovalRepository,
            KycDocumentRepository kycDocumentRepository,
            KycApprovalRecordRepository approvalRecordRepository,
            KycEvidenceService evidenceService,
            KycService kycService,
            ScreeningGate screeningGate) {
        this.legalEntityRepository = legalEntityRepository;
        this.beneficialOwnerRepository = beneficialOwnerRepository;
        this.naturalPersonRepository = naturalPersonRepository;
        this.eddApprovalRepository = eddApprovalRepository;
        this.kycDocumentRepository = kycDocumentRepository;
        this.approvalRecordRepository = approvalRecordRepository;
        this.evidenceService = evidenceService;
        this.kycService = kycService;
        this.screeningGate = screeningGate;
    }

    /**
     * Entities that need a decision or a follow-up: KYC in progress (awaiting a decision), expired or expiring
     * KYC, approved entities that would not pass today's checks, and unverified beneficial owners. Entities
     * that never started KYC and rejected ones are not queued.
     */
    public List<QueueItem> queue() {
        LocalDate today = LocalDate.now();
        List<QueueItem> items = new ArrayList<>();
        for (KycStatus status : List.of(KycStatus.IN_PROGRESS, KycStatus.EXPIRED, KycStatus.APPROVED)) {
            for (LegalEntity entity : legalEntityRepository.findByKycStatus(status)) {
                List<String> reasons = reasonsOf(entity, status, today);
                if (!reasons.isEmpty()) {
                    items.add(new QueueItem(entity.getId(), entity.getCurrentName(),
                            KycEvidenceService.homeJurisdiction(entity), status, entity.getKycExpiryDate(), reasons));
                }
            }
        }
        items.sort(Comparator.comparingInt((QueueItem i) -> priority(i.kycStatus()))
                .thenComparing(QueueItem::kycExpiryDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(QueueItem::entityName, Comparator.nullsLast(String::compareToIgnoreCase)));
        return items;
    }

    private static int priority(KycStatus status) {
        return switch (status) {
            case IN_PROGRESS -> 0;
            case EXPIRED -> 1;
            default -> 2;
        };
    }

    private List<String> reasonsOf(LegalEntity entity, KycStatus status, LocalDate today) {
        List<String> reasons = new ArrayList<>();
        switch (status) {
            case IN_PROGRESS -> reasons.add("KYC_IN_PROGRESS");
            case EXPIRED -> reasons.add("KYC_EXPIRED");
            default -> {
                reasons.addAll(evidenceService.expiryGapsOf(entity, today));
                LocalDate expiry = entity.getKycExpiryDate();
                if (expiry != null && !expiry.isBefore(today) && !expiry.isAfter(today.plusDays(EXPIRY_WARNING_DAYS))) {
                    reasons.add("KYC_EXPIRING");
                }
            }
        }
        reasons.addAll(evidenceService.evidenceGapsOf(entity));
        if (hasUnverifiedOwner(entity.getId())) {
            reasons.add("BO_UNVERIFIED");
        }
        return reasons;
    }

    private boolean hasUnverifiedOwner(UUID entityId) {
        return beneficialOwnerRepository.findByEntityIdAndCeasedAtIsNull(entityId).stream()
                .anyMatch(bo -> bo.getControlType() != BeneficialOwner.ControlType.SENIOR_MANAGING_OFFICIAL
                        && bo.getVerifiedAt() == null);
    }

    public Review review(UUID entityId) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
        Jurisdiction home = KycEvidenceService.homeJurisdiction(entity);
        KycEvidenceService.Evidence evidence = evidenceService.evaluate(entityId, home);

        List<String> gaps = new ArrayList<>();
        if (entity.getKycStatus() == KycStatus.APPROVED) {
            gaps.addAll(evidenceService.expiryGapsOf(entity, LocalDate.now()));
        }
        gaps.addAll(evidenceService.evidenceGapsOf(entity, evidence));

        return new Review(entity, home,
                kycDocumentRepository.findByLegalEntityIdAndDeletedAtIsNull(entityId),
                evidence.checklist(),
                kycService.getJurisdictionApprovals(entityId),
                evidence.ownership(),
                owners(entityId),
                new ScreeningState(screeningGate.hasUnresolvedHit(entityId),
                        screeningGate.hasUnresolvedBeneficialOwnerHit(entityId),
                        screeningGate.isRelyingOnStaleResult(entityId)),
                gaps,
                approvalRecordRepository.findByEntityIdOrderByCreatedAtDesc(entityId));
    }

    private List<OwnerView> owners(UUID entityId) {
        List<BeneficialOwner> active = beneficialOwnerRepository.findByEntityIdAndCeasedAtIsNull(entityId);
        List<UUID> personIds = active.stream().map(BeneficialOwner::getNaturalPersonId).toList();
        Map<UUID, NaturalPerson> persons = naturalPersonRepository.findAllById(personIds).stream()
                .collect(Collectors.toMap(NaturalPerson::getId, Function.identity()));
        Map<UUID, Instant> eddDue = personIds.isEmpty() ? Map.of()
                : eddApprovalRepository.findByNaturalPersonIdInAndReviewDueAfter(personIds, Instant.now()).stream()
                        .collect(Collectors.toMap(EddApproval::getNaturalPersonId, EddApproval::getReviewDue,
                                (a, b) -> a.isAfter(b) ? a : b));
        List<OwnerView> views = new ArrayList<>();
        for (BeneficialOwner bo : active) {
            NaturalPerson person = persons.get(bo.getNaturalPersonId());
            if (person == null) {
                throw new EntityNotFoundException("NaturalPerson", bo.getNaturalPersonId());
            }
            views.add(new OwnerView(bo, person,
                    screeningGate.hasUnresolvedHitForPerson(person.getId()),
                    Optional.ofNullable(eddDue.get(person.getId()))));
        }
        return views;
    }
}
