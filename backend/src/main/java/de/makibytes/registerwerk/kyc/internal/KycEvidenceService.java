package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.BeneficialOwnerRepository;
import de.makibytes.registerwerk.kyc.api.EddApproval;
import de.makibytes.registerwerk.kyc.api.EddApprovalRepository;
import de.makibytes.registerwerk.kyc.api.KycComplianceService;
import de.makibytes.registerwerk.kyc.api.NaturalPerson;
import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The evidence an entity-level KYC approval rests on (6-15, 6-17): the document checklist of the
 * entity's home jurisdiction, beneficial-owner coverage, and the review dates of confirmed PEPs.
 * One computation shared by {@link KycService#approveKyc} and the read-only evidence-gap report, so the
 * report lists exactly the entities the approval would now refuse. The thresholds are interim values of
 * parked decisions T6-01/T6-02, not a risk methodology.
 */
@Service
@Transactional(readOnly = true)
public class KycEvidenceService {

    /** Share of the ownership that must be identified unless a documented SMO fallback exists (T6-01). */
    public static final BigDecimal MIN_IDENTIFIED_PCT = new BigDecimal("75");
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private final LegalEntityRepository legalEntityRepository;
    private final BeneficialOwnerRepository beneficialOwnerRepository;
    private final NaturalPersonRepository naturalPersonRepository;
    private final EddApprovalRepository eddApprovalRepository;
    private final KycComplianceService kycComplianceService;
    private final ScreeningGate screeningGate;
    private final int maxValidityMonths;

    public KycEvidenceService(
            LegalEntityRepository legalEntityRepository,
            BeneficialOwnerRepository beneficialOwnerRepository,
            NaturalPersonRepository naturalPersonRepository,
            EddApprovalRepository eddApprovalRepository,
            KycComplianceService kycComplianceService,
            ScreeningGate screeningGate,
            @Value("${registerwerk.kyc.max-validity-months:12}") int maxValidityMonths) {
        this.legalEntityRepository = legalEntityRepository;
        this.beneficialOwnerRepository = beneficialOwnerRepository;
        this.naturalPersonRepository = naturalPersonRepository;
        this.eddApprovalRepository = eddApprovalRepository;
        this.kycComplianceService = kycComplianceService;
        this.screeningGate = screeningGate;
        this.maxValidityMonths = maxValidityMonths;
    }

    /** Beneficial-owner coverage of one entity. {@code unexplainedPct} = 100 - identified (never negative). */
    public record OwnershipSummary(
            int activeCount,
            BigDecimal identifiedPct,
            BigDecimal unexplainedPct,
            boolean smoFallback,
            boolean identifiedOwnerPresent,
            boolean coverageSufficient) {}

    public record Evidence(
            Jurisdiction jurisdiction,
            KycComplianceService.ComplianceResult checklist,
            OwnershipSummary ownership,
            Optional<Instant> earliestPepReviewDue,
            boolean confirmedPepWithoutEdd) {}

    public record Gap(UUID entityId, String entityName, LocalDate kycExpiryDate, List<String> gaps) {}

    public int maxValidityMonths() {
        return maxValidityMonths;
    }

    public LocalDate latestAllowedExpiry(LocalDate today) {
        return today.plusMonths(maxValidityMonths);
    }

    /** Home jurisdiction from the registration country; the operator's own (DE) when it is not a configured one. */
    public static Jurisdiction homeJurisdiction(LegalEntity entity) {
        String country = entity.getRegistrationCountry();
        if (country != null) {
            switch (country.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "LU": return Jurisdiction.LU_CSSF;
                case "FR": return Jurisdiction.FR_AMF;
                case "LI": return Jurisdiction.LI_TVTG;
                default: break;
            }
        }
        return Jurisdiction.DE_EWPG;
    }

    public OwnershipSummary ownershipSummary(UUID entityId) {
        List<BeneficialOwner> active = beneficialOwnerRepository.findByEntityIdAndCeasedAtIsNull(entityId);
        BigDecimal identified = BigDecimal.ZERO;
        boolean smo = false;
        boolean identifiedOwner = false;
        for (BeneficialOwner bo : active) {
            if (bo.getControlType() == BeneficialOwner.ControlType.SENIOR_MANAGING_OFFICIAL) {
                smo = true;
                continue;
            }
            identifiedOwner = true;
            if (bo.getOwnershipPct() != null) {
                identified = identified.add(bo.getOwnershipPct());
            }
        }
        BigDecimal identifiedCapped = identified.min(HUNDRED);
        BigDecimal unexplained = HUNDRED.subtract(identifiedCapped);
        boolean sufficient = !active.isEmpty()
                && (smo || identified.compareTo(MIN_IDENTIFIED_PCT) >= 0);
        return new OwnershipSummary(active.size(), identified, unexplained, smo, identifiedOwner, sufficient);
    }

    public Evidence evaluate(UUID entityId, Jurisdiction jurisdiction) {
        KycComplianceService.ComplianceResult checklist = kycComplianceService.checkCompliance(entityId, jurisdiction);
        OwnershipSummary ownership = ownershipSummary(entityId);

        List<UUID> personIds = beneficialOwnerRepository.findByEntityIdAndCeasedAtIsNull(entityId).stream()
                .map(BeneficialOwner::getNaturalPersonId).toList();
        Map<UUID, NaturalPerson> persons = naturalPersonRepository.findAllById(personIds).stream()
                .collect(Collectors.toMap(NaturalPerson::getId, Function.identity()));
        List<UUID> pepIds = persons.values().stream()
                .filter(p -> p.getPepStatus() == NaturalPerson.PepStatus.CONFIRMED_PEP)
                .map(NaturalPerson::getId).toList();
        Optional<Instant> earliest = Optional.empty();
        boolean withoutEdd = false;
        if (!pepIds.isEmpty()) {
            Collection<EddApproval> inForce = eddApprovalRepository
                    .findByNaturalPersonIdInAndReviewDueAfter(pepIds, Instant.now());
            Map<UUID, Instant> latestDue = inForce.stream().collect(Collectors.toMap(
                    EddApproval::getNaturalPersonId, EddApproval::getReviewDue, (a, b) -> a.isAfter(b) ? a : b));
            withoutEdd = pepIds.stream().anyMatch(id -> !latestDue.containsKey(id));
            earliest = latestDue.values().stream().min(Instant::compareTo);
        }
        return new Evidence(jurisdiction, checklist, ownership, earliest, withoutEdd);
    }

    /**
     * APPROVED entities that would not pass the approval checks today. Existing approvals are not
     * downgraded (T6-01 interim); the compliance officer works the list at the next review.
     */
    public List<Gap> evidenceGaps() {
        LocalDate today = LocalDate.now();
        List<Gap> result = new ArrayList<>();
        for (LegalEntity entity : legalEntityRepository.findByKycStatus(KycStatus.APPROVED)) {
            List<String> gaps = gapsOf(entity, today);
            if (!gaps.isEmpty()) {
                result.add(new Gap(entity.getId(), entity.getCurrentName(), entity.getKycExpiryDate(), gaps));
            }
        }
        return result;
    }

    List<String> gapsOf(LegalEntity entity, LocalDate today) {
        List<String> gaps = new ArrayList<>(expiryGapsOf(entity, today));
        gaps.addAll(evidenceGapsOf(entity));
        return gaps;
    }

    /** Expiry findings of an approved entity: already expired, or valid beyond the configured maximum. */
    List<String> expiryGapsOf(LegalEntity entity, LocalDate today) {
        LocalDate expiry = entity.getKycExpiryDate();
        if (expiry == null || expiry.isBefore(today)) {
            return List.of("KYC_EXPIRED");
        }
        if (expiry.isAfter(latestAllowedExpiry(today))) {
            return List.of("EXPIRY_BEYOND_MAX_VALIDITY");
        }
        return List.of();
    }

    /** What would block an approval today: checklist, beneficial-owner coverage, PEP/EDD and screening. */
    List<String> evidenceGapsOf(LegalEntity entity) {
        return evidenceGapsOf(entity, evaluate(entity.getId(), homeJurisdiction(entity)));
    }

    List<String> evidenceGapsOf(LegalEntity entity, Evidence evidence) {
        List<String> gaps = new ArrayList<>();
        KycComplianceService.ComplianceResult c = evidence.checklist();
        if (!c.fullyCompliant()) {
            gaps.add("CHECKLIST_INCOMPLETE (missing=" + c.missingCount() + ", expired=" + c.expiredCount()
                    + ", tooOld=" + c.tooOldCount() + ")");
        }
        OwnershipSummary o = evidence.ownership();
        if (o.activeCount() == 0) {
            gaps.add("NO_BENEFICIAL_OWNER");
        } else if (!o.coverageSufficient()) {
            gaps.add("OWNERSHIP_UNEXPLAINED (identified=" + o.identifiedPct().toPlainString() + "%)");
        }
        if (evidence.confirmedPepWithoutEdd()) {
            gaps.add("PEP_WITHOUT_EDD_APPROVAL");
        }
        if (screeningGate.hasUnresolvedHit(entity.getId())) {
            gaps.add("ENTITY_SCREENING_UNRESOLVED");
        }
        if (screeningGate.hasUnresolvedBeneficialOwnerHit(entity.getId())) {
            gaps.add("BENEFICIAL_OWNER_SCREENING_UNRESOLVED");
        }
        return gaps;
    }
}
