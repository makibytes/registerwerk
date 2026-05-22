package de.makibytes.registerwerk.kyc.internal;

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

import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import de.makibytes.registerwerk.kyc.events.KycRejectedEvent;
import de.makibytes.registerwerk.kyc.events.KycJurisdictionApprovedEvent;
import de.makibytes.registerwerk.kyc.events.KycJurisdictionRejectedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.kyc.api.KycStatus;
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

    public KycService(
            LegalEntityRepository legalEntityRepository,
            KycJurisdictionApprovalRepository jurisdictionApprovalRepository,
            ApplicationEventPublisher eventPublisher,
            ScreeningGate screeningGate) {
        this.legalEntityRepository = legalEntityRepository;
        this.jurisdictionApprovalRepository = jurisdictionApprovalRepository;
        this.eventPublisher = eventPublisher;
        this.screeningGate = screeningGate;
    }

    /**
     * Approves KYC for the given entity and sets the expiry date.
     * GwG §10: blocked if entity or any beneficial owner has an unresolved sanctions hit.
     */
    public void approveKyc(UUID entityId, LocalDate expiryDate, UUID actorId) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));

        if (screeningGate.hasUnresolvedHit(entityId)) {
            throw new IllegalStateException(
                "Cannot approve KYC: entity has an unresolved sanctions screening hit. " +
                "A compliance officer must review and dismiss the hit first (GwG §10).");
        }
        if (screeningGate.hasUnresolvedBeneficialOwnerHit(entityId)) {
            throw new IllegalStateException(
                "Cannot approve KYC: a beneficial owner has an unresolved sanctions screening hit. " +
                "A compliance officer must review and dismiss the hit first (GwG §11).");
        }

        entity.setKycStatus(KycStatus.APPROVED);
        entity.setKycExpiryDate(expiryDate);
        legalEntityRepository.save(entity);
        eventPublisher.publishEvent(new KycApprovedEvent(entityId, actorId, null, java.util.Map.of("expiryDate", expiryDate.toString())));
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
        eventPublisher.publishEvent(new KycRejectedEvent(entityId, actorId, null, java.util.Map.of("reason", reason != null ? reason : "")));
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
