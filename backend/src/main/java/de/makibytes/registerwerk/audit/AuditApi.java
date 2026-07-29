package de.makibytes.registerwerk.audit;

import de.makibytes.registerwerk.audit.api.AuditEventView;
import de.makibytes.registerwerk.audit.api.ChainVerificationView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Public API for querying the audit log. */
public interface AuditApi {

    Page<AuditEventView> findBySubject(String subjectType, UUID subjectId, Pageable pageable);

    Page<AuditEventView> findByEventType(String eventType, Pageable pageable);

    Page<AuditEventView> findByActor(UUID actorId, Pageable pageable);

    Page<AuditEventView> findAll(Pageable pageable);

    Optional<AuditEventView> findById(UUID id);

    Page<AuditEventView> findKycOverrideApprovals(String jurisdiction, Instant from, Instant to, Pageable pageable);

    /** Most recently computed hash-chain verification result, without triggering a new scan. */
    ChainVerificationView chainVerificationStatus();

    /** Runs a full hash-chain verification scan now and returns the result. */
    ChainVerificationView verifyChainNow();
}
