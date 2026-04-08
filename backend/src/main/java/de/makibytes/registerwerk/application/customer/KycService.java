package de.makibytes.registerwerk.application.customer;

import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.entity.LegalEntity;
import de.makibytes.registerwerk.domain.enums.KycStatus;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.LegalEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Manages KYC approval, rejection, and status queries for legal entities.
 */
@Service
@Transactional
public class KycService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    private final LegalEntityRepository legalEntityRepository;
    private final AuditEventPublisher auditEventPublisher;

    public KycService(
            LegalEntityRepository legalEntityRepository,
            AuditEventPublisher auditEventPublisher) {
        this.legalEntityRepository = legalEntityRepository;
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
}
