package de.makibytes.registerwerk.application.customer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.entity.LegalEntity;
import de.makibytes.registerwerk.domain.enums.Jurisdiction;
import de.makibytes.registerwerk.domain.enums.KycStatus;
import de.makibytes.registerwerk.domain.kyc.KycJurisdictionApproval;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.KycJurisdictionApprovalRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.LegalEntityRepository;

/**
 * Manages KYC approval, rejection, and status queries for legal entities.
 */
@Service
@Transactional
public class KycService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    private final LegalEntityRepository legalEntityRepository;
    private final KycJurisdictionApprovalRepository jurisdictionApprovalRepository;
    private final AuditEventPublisher auditEventPublisher;

    public KycService(
            LegalEntityRepository legalEntityRepository,
            KycJurisdictionApprovalRepository jurisdictionApprovalRepository,
            AuditEventPublisher auditEventPublisher) {
        this.legalEntityRepository = legalEntityRepository;
        this.jurisdictionApprovalRepository = jurisdictionApprovalRepository;
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Approves KYC for the given entity and sets the expiry date.
     */
    public void approveKyc(UUID entityId, LocalDate expiryDate, UUID actorId) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
        entity.setKycStatus(KycStatus.APPROVED);
        entity.setKycExpiryDate(expiryDate);
        legalEntityRepository.save(entity);
        auditEventPublisher.publish("KYC_APPROVED", "LegalEntity", entityId, actorId, null,
            Map.of("expiryDate", expiryDate.toString()));
        log.info("KYC approved for entityId={}", entityId);
    }

    /**
     * Rejects KYC for the given entity, recording the rejection reason.
     */
    public void rejectKyc(UUID entityId, String reason, UUID actorId) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
        entity.setKycStatus(KycStatus.REJECTED);
        legalEntityRepository.save(entity);
        auditEventPublisher.publish("KYC_REJECTED", "LegalEntity", entityId, actorId, null,
            Map.of("reason", reason != null ? reason : ""));
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
        auditEventPublisher.publish("KYC_JURISDICTION_APPROVED", "LegalEntity", entityId, actorId, null,
            Map.of(
                "jurisdiction", jurisdiction.name(),
                "expiresAt", saved.getExpiresAt().toString(),
                "overrideApplied", overrideNote != null && !overrideNote.isBlank(),
                "missingCount", missingCount,
                "expiredCount", expiredCount,
                "tooOldCount", tooOldCount
            ));
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
        auditEventPublisher.publish("KYC_JURISDICTION_REJECTED", "LegalEntity", entityId, actorId, null,
            Map.of("jurisdiction", jurisdiction.name(), "reason", reason != null ? reason : ""));
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
