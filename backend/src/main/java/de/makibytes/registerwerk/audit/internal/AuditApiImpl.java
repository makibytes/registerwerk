package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.AuditApi;
import de.makibytes.registerwerk.audit.api.AuditEventView;
import de.makibytes.registerwerk.audit.api.ChainVerificationView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class AuditApiImpl implements AuditApi {

    private final AuditEventRepository repository;
    private final AuditChainVerificationService chainVerificationService;

    AuditApiImpl(AuditEventRepository repository, AuditChainVerificationService chainVerificationService) {
        this.repository = repository;
        this.chainVerificationService = chainVerificationService;
    }

    @Override
    public Page<AuditEventView> findBySubject(String subjectType, UUID subjectId, Pageable pageable) {
        return repository.findBySubjectTypeAndSubjectId(subjectType, subjectId, pageable).map(this::toView);
    }

    @Override
    public Page<AuditEventView> findByEventType(String eventType, Pageable pageable) {
        return repository.findByEventType(eventType, pageable).map(this::toView);
    }

    @Override
    public Page<AuditEventView> findByActor(UUID actorId, Pageable pageable) {
        return repository.findByActorId(actorId, pageable).map(this::toView);
    }

    @Override
    public Page<AuditEventView> findAll(Pageable pageable) {
        return repository.findAll(pageable).map(this::toView);
    }

    @Override
    public Optional<AuditEventView> findById(UUID id) {
        return repository.findById(id).map(this::toView);
    }

    @Override
    public Page<AuditEventView> findKycOverrideApprovals(String jurisdiction, Instant from, Instant to, Pageable pageable) {
        return repository.findKycOverrideApprovals(jurisdiction, from, to, pageable).map(this::toView);
    }

    @Override
    public ChainVerificationView chainVerificationStatus() {
        return toView(chainVerificationService.lastResult());
    }

    @Override
    public ChainVerificationView verifyChainNow() {
        return toView(chainVerificationService.verifyNow());
    }

    private ChainVerificationView toView(AuditChainVerificationService.VerificationResult r) {
        return new ChainVerificationView(r.valid(), r.rowsChecked(), r.firstBrokenSeq(), r.checkedAt());
    }

    private AuditEventView toView(AuditEvent e) {
        return new AuditEventView(e.getId(), e.getEventType(), e.getSubjectType(), e.getSubjectId(),
                e.getActorId(), e.getActorRole(), e.getPayload(), e.getOccurredAt());
    }
}
