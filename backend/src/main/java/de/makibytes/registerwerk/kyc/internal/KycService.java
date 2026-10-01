package de.makibytes.registerwerk.kyc.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import de.makibytes.registerwerk.kyc.events.KycRejectedEvent;
import de.makibytes.registerwerk.kyc.events.KycRejectionCategory;
import de.makibytes.registerwerk.kyc.events.KycJurisdictionApprovedEvent;
import de.makibytes.registerwerk.kyc.events.KycJurisdictionRejectedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.springframework.security.access.AccessDeniedException;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.kyc.api.KycApprovalRecord;
import de.makibytes.registerwerk.kyc.api.KycApprovalRecordRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.kyc.api.KycJurisdictionApproval;
import de.makibytes.registerwerk.kyc.api.KycJurisdictionApprovalRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;

/**
 * Manages KYC approval, rejection, and status queries for legal entities.
 */
@Service
@Transactional
public class KycService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    private final LegalEntityRepository legalEntityRepository;
    private final KycJurisdictionApprovalRepository jurisdictionApprovalRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ScreeningGate screeningGate;
    private final KycEvidenceService evidenceService;
    private final KycApprovalRecordRepository approvalRecordRepository;

    public KycService(
            LegalEntityRepository legalEntityRepository,
            KycJurisdictionApprovalRepository jurisdictionApprovalRepository,
            ApplicationEventPublisher eventPublisher,
            ScreeningGate screeningGate,
            KycEvidenceService evidenceService,
            KycApprovalRecordRepository approvalRecordRepository) {
        this.legalEntityRepository = legalEntityRepository;
        this.jurisdictionApprovalRepository = jurisdictionApprovalRepository;
        this.eventPublisher = eventPublisher;
        this.screeningGate = screeningGate;
        this.evidenceService = evidenceService;
        this.approvalRecordRepository = approvalRecordRepository;
    }

    /**
     * Approves KYC for the given entity (6-15). Beyond the sanctions gates (GwG s.10/s.11) it requires:
     * entity status ACTIVE or PENDING_ONBOARDING; the document checklist of the home jurisdiction
     * (or an override note, REGISTRY_ADMIN only, exactly as on the jurisdiction path); at least one
     * beneficial owner with identified ownership of 75 % or more, or a documented senior-managing-official
     * fallback (which, like an incomplete checklist, needs the override note); an expiry within
     * {@code registerwerk.kyc.max-validity-months} (default 12) that does not outlive the review date of a
     * linked confirmed PEP. Interim values of parked decisions T6-01/T6-02.
     *
     * @param approverId  validated second approver (dual control), recorded in the evidence snapshot
     * @param jurisdiction checklist jurisdiction; null = derived from the registration country
     */
    public void approveKyc(UUID entityId, LocalDate expiryDate, UUID actorId, UUID approverId,
                           Jurisdiction jurisdiction, String overrideNote, boolean registryAdmin) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));

        if (entity.getStatus() != EntityStatus.ACTIVE && entity.getStatus() != EntityStatus.PENDING_ONBOARDING) {
            throw new InvalidStateTransitionException(
                "Cannot approve KYC: the entity is " + entity.getStatus() + ", only ACTIVE or PENDING_ONBOARDING entities can be approved.");
        }
        if (screeningGate.hasUnresolvedHit(entityId)) {
            throw new ComplianceGateException(
                "Cannot approve KYC: no clear sanctions screening result exists for this entity. " +
                "Either the entity was never screened, the latest screening is pending or failed, " +
                "or there is an unresolved hit that a compliance officer must review first (GwG §10).");
        }
        if (screeningGate.hasUnresolvedBeneficialOwnerHit(entityId)) {
            throw new ComplianceGateException(
                "Cannot approve KYC: a beneficial owner has an unresolved sanctions screening hit. " +
                "A compliance officer must review and dismiss the hit first (GwG §11).");
        }

        Jurisdiction checklistJurisdiction = jurisdiction != null ? jurisdiction : KycEvidenceService.homeJurisdiction(entity);
        KycEvidenceService.Evidence evidence = evidenceService.evaluate(entityId, checklistJurisdiction);
        KycEvidenceService.OwnershipSummary ownership = evidence.ownership();
        if (ownership.activeCount() == 0) {
            throw new ComplianceGateException(
                "Cannot approve KYC: no beneficial owner is recorded. Register the beneficial owners (GwG §3) or a "
                + "documented senior-managing-official fallback first.");
        }
        if (!ownership.coverageSufficient()) {
            throw new ComplianceGateException(
                "Cannot approve KYC: only " + ownership.identifiedPct().toPlainString() + " % of the ownership is identified "
                + "(at least " + KycEvidenceService.MIN_IDENTIFIED_PCT.toPlainString() + " % required, or record a documented "
                + "senior-managing-official fallback).");
        }
        if (evidence.confirmedPepWithoutEdd()) {
            throw new ComplianceGateException(
                "Cannot approve KYC: a beneficial owner is a confirmed PEP without an unexpired EDD approval.");
        }

        List<String> overrideReasons = new java.util.ArrayList<>();
        if (!evidence.checklist().fullyCompliant()) {
            overrideReasons.add("checklist incomplete for " + checklistJurisdiction.name());
        }
        if (!ownership.identifiedOwnerPresent() || ownership.identifiedPct().compareTo(KycEvidenceService.MIN_IDENTIFIED_PCT) < 0) {
            overrideReasons.add("ownership coverage rests on the senior-managing-official fallback");
        }
        String note = overrideNote != null ? overrideNote.trim() : "";
        if (!overrideReasons.isEmpty()) {
            if (note.isEmpty()) {
                throw new IllegalArgumentException("KYC cannot be approved as is (" + String.join("; ", overrideReasons)
                    + "). Add overrideNote to approve as risk acceptance.");
            }
            if (!registryAdmin) {
                throw new AccessDeniedException("Only REGISTRY_ADMIN may approve with overrideNote (" + String.join("; ", overrideReasons) + ").");
            }
        }

        LocalDate today = LocalDate.now();
        LocalDate maxExpiry = evidenceService.latestAllowedExpiry(today);
        if (expiryDate != null && expiryDate.isAfter(maxExpiry)) {
            throw new IllegalArgumentException("expiryDate " + expiryDate + " exceeds the maximum validity of "
                + evidenceService.maxValidityMonths() + " months (latest " + maxExpiry + ").");
        }
        LocalDate effectiveExpiry = expiryDate != null ? expiryDate : maxExpiry;
        String cappedBy = null;
        if (evidence.earliestPepReviewDue().isPresent()) {
            LocalDate pepCap = evidence.earliestPepReviewDue().get().atZone(ZoneOffset.UTC).toLocalDate();
            if (pepCap.isBefore(effectiveExpiry)) {
                effectiveExpiry = pepCap;
                cappedBy = "PEP_EDD_REVIEW_DUE";
            }
        }

        entity.setKycStatus(KycStatus.APPROVED);
        entity.setKycExpiryDate(effectiveExpiry);
        legalEntityRepository.save(entity);

        String snapshot = checklistSnapshot(evidence);
        KycApprovalRecord record = new KycApprovalRecord();
        record.setEntityId(entityId);
        record.setApprovedBy(actorId);
        record.setSecondApproverId(approverId);
        record.setJurisdiction(checklistJurisdiction);
        record.setExpiryDate(effectiveExpiry);
        record.setChecklistCompliant(evidence.checklist().fullyCompliant());
        record.setOverrideNote(note.isEmpty() ? null : note);
        record.setIdentifiedPct(ownership.identifiedPct());
        record.setSmoFallback(ownership.smoFallback());
        record.setEvidenceSnapshot(snapshot);
        approvalRecordRepository.save(record);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("expiryDate", effectiveExpiry.toString());
        payload.put("jurisdiction", checklistJurisdiction.name());
        payload.put("checklistCompliant", evidence.checklist().fullyCompliant());
        payload.put("checklistSnapshot", snapshot);
        payload.put("identifiedOwnershipPct", ownership.identifiedPct().toPlainString());
        payload.put("smoFallback", ownership.smoFallback());
        payload.put("overrideNote", note);
        if (cappedBy != null) payload.put("expiryCappedBy", cappedBy);
        if (approverId != null) payload.put("dualControlApproverId", approverId.toString());
        eventPublisher.publishEvent(new KycApprovedEvent(entityId, actorId, null, payload));
        log.info("KYC approved for entityId={}", entityId);
    }

    private static String checklistSnapshot(KycEvidenceService.Evidence evidence) {
        return evidence.checklist().documents().stream()
            .map(d -> d.documentType().name() + "=" + (!d.present() ? "MISSING" : d.expired() ? "EXPIRED" : d.tooOld() ? "TOO_OLD" : "OK")
                + (d.mandatory() ? "" : "(optional)"))
            .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Rejects KYC for the given entity. {@code internalReason} is the operator's free text: it goes to
     * the audit trail only (payload key {@code internalReason}). Webhooks and e-mails receive just the
     * fixed {@code customerReasonCode} category (GwG s.47 tipping-off; parked decision T5-14).
     */
    public void rejectKyc(UUID entityId, String internalReason, KycRejectionCategory customerReasonCode, UUID actorId) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
        entity.setKycStatus(KycStatus.REJECTED);
        legalEntityRepository.save(entity);
        eventPublisher.publishEvent(new KycRejectedEvent(entityId, actorId, null, java.util.Map.of(
            "internalReason", internalReason != null ? internalReason : "",
            "reasonCode", (customerReasonCode != null ? customerReasonCode : KycRejectionCategory.CONTACT_SUPPORT).name())));
        log.info("KYC rejected for entityId={}", entityId);
    }

    /**
     * Returns the current KYC status for the given entity.
     *
     * @throws EntityNotFoundException if entity does not exist
     */
    @Transactional(readOnly = true)
    public KycStatus getKycStatus(UUID entityId) {
        return legalEntityRepository.findById(entityId)
            .map(LegalEntity::getKycStatus)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
    }

    // ── Per-jurisdiction KYC approval ─────────────────────────────────────────

    /**
     * Approves KYC for a specific jurisdiction. Creates or updates the
     * {@link KycJurisdictionApproval} record.
     */
    public KycJurisdictionApproval approveKycForJurisdiction(
            UUID entityId,
            Jurisdiction jurisdiction,
            LocalDate expiresAt,
            UUID actorId,
            String overrideNote,
            int missingCount,
            int expiredCount,
            int tooOldCount) {

        legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));

        // Sanctions screening blocks are NOT overridable — unlike checklist gaps,
        // an overrideNote cannot waive EU sanctions law (GwG §10, §11). The block is
        // lifted by running a screening or by a compliance officer resolving open hits.
        if (screeningGate.hasUnresolvedHit(entityId)) {
            throw new ComplianceGateException(
                "Cannot approve KYC for jurisdiction " + jurisdiction.name() + ": no clear sanctions " +
                "screening result exists for this entity. Run a screening or resolve open hits first (GwG §10).");
        }
        if (screeningGate.hasUnresolvedBeneficialOwnerHit(entityId)) {
            throw new ComplianceGateException(
                "Cannot approve KYC for jurisdiction " + jurisdiction.name() + ": a beneficial owner has " +
                "no clear sanctions screening result. Resolve the screening condition first (GwG §11).");
        }

        KycJurisdictionApproval approval = jurisdictionApprovalRepository
            .findByEntityIdAndJurisdiction(entityId, jurisdiction)
            .orElseGet(() -> {
                KycJurisdictionApproval a = new KycJurisdictionApproval();
                a.setEntityId(entityId);
                a.setJurisdiction(jurisdiction);
                return a;
            });

        approval.setStatus(KycJurisdictionApproval.Status.APPROVED);
        approval.setApprovedBy(actorId);
        approval.setApprovedAt(Instant.now());
        approval.setExpiresAt(expiresAt != null ? expiresAt : LocalDate.now().plusYears(1));
        approval.setRejectionReason(null);
        approval.setOverrideNote(overrideNote);

        KycJurisdictionApproval saved = jurisdictionApprovalRepository.save(approval);
        eventPublisher.publishEvent(new KycJurisdictionApprovedEvent(entityId, actorId, null, java.util.Map.of("jurisdiction", jurisdiction.name(), "expiresAt", saved.getExpiresAt().toString())));
        log.info("KYC approved for entityId={}, jurisdiction={}", entityId, jurisdiction);
        return saved;
    }

    /**
     * Rejects KYC for a specific jurisdiction.
     */
    public KycJurisdictionApproval rejectKycForJurisdiction(
            UUID entityId, Jurisdiction jurisdiction, String reason, UUID actorId) {

        legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));

        KycJurisdictionApproval approval = jurisdictionApprovalRepository
            .findByEntityIdAndJurisdiction(entityId, jurisdiction)
            .orElseGet(() -> {
                KycJurisdictionApproval a = new KycJurisdictionApproval();
                a.setEntityId(entityId);
                a.setJurisdiction(jurisdiction);
                return a;
            });

        approval.setStatus(KycJurisdictionApproval.Status.REJECTED);
        approval.setRejectionReason(reason);
        approval.setApprovedBy(actorId);
        approval.setApprovedAt(Instant.now());

        KycJurisdictionApproval saved = jurisdictionApprovalRepository.save(approval);
        eventPublisher.publishEvent(new KycJurisdictionRejectedEvent(entityId, actorId, null, java.util.Map.of("jurisdiction", jurisdiction.name(), "reason", reason != null ? reason : "")));
        log.info("KYC rejected for entityId={}, jurisdiction={}", entityId, jurisdiction);
        return saved;
    }

    /**
     * Returns all per-jurisdiction KYC approval records for an entity.
     */
    @Transactional(readOnly = true)
    public List<KycJurisdictionApproval> getJurisdictionApprovals(UUID entityId) {
        return jurisdictionApprovalRepository.findByEntityId(entityId);
    }

    /**
     * Returns the per-jurisdiction KYC approval for a single jurisdiction.
     */
    @Transactional(readOnly = true)
    public Optional<KycJurisdictionApproval> getJurisdictionApproval(UUID entityId, Jurisdiction jurisdiction) {
        return jurisdictionApprovalRepository.findByEntityIdAndJurisdiction(entityId, jurisdiction);
    }
}
