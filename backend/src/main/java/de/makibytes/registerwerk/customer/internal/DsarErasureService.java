package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.customer.api.ErasureRequest;
import de.makibytes.registerwerk.customer.api.ErasureRequestRepository;
import de.makibytes.registerwerk.customer.api.ErasureRequestStatus;
import de.makibytes.registerwerk.customer.events.DsarErasureRequestedEvent;
import de.makibytes.registerwerk.customer.events.DsarErasureResolvedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the lifecycle of DSGVO Art. 17 erasure requests: filing (by the data subject)
 * and resolution (by an operator, after weighing statutory retention). The request is
 * persisted so it becomes an operator work item with a 30-day response clock, instead
 * of being acknowledged and dropped.
 */
@Service
@Transactional
public class DsarErasureService {

    private static final Logger log = LoggerFactory.getLogger(DsarErasureService.class);

    /** DSGVO Art. 12(3): respond within one month. */
    private static final Duration RESPONSE_WINDOW = Duration.ofDays(30);

    private static final List<ErasureRequestStatus> OPEN =
            List.of(ErasureRequestStatus.REQUESTED, ErasureRequestStatus.IN_REVIEW);

    private final ErasureRequestRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public DsarErasureService(ErasureRequestRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Files an erasure request for {@code entityId}. Idempotent: if an open request
     * already exists it is returned unchanged, so repeated submissions do not stack.
     */
    public ErasureRequest request(UUID entityId, UUID requestedByUserId) {
        return repository.findFirstByEntityIdAndStatusInOrderByRequestedAtAsc(entityId, OPEN)
                .orElseGet(() -> createRequest(entityId, requestedByUserId));
    }

    private ErasureRequest createRequest(UUID entityId, UUID requestedByUserId) {
        Instant now = Instant.now();
        ErasureRequest req = new ErasureRequest();
        req.setEntityId(entityId);
        req.setRequestedByUserId(requestedByUserId);
        req.setStatus(ErasureRequestStatus.REQUESTED);
        req.setRequestedAt(now);
        req.setDueAt(now.plus(RESPONSE_WINDOW));
        ErasureRequest saved = repository.save(req);

        Map<String, Object> details = new HashMap<>();
        details.put("erasureRequestId", saved.getId().toString());
        details.put("dueAt", saved.getDueAt().toString());
        eventPublisher.publishEvent(new DsarErasureRequestedEvent(entityId, requestedByUserId, "CUSTOMER", details));
        log.warn("DSAR erasure requested for entityId={} (due {})", entityId, saved.getDueAt());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ErasureRequest> listOpen() {
        return repository.findByStatusInOrderByRequestedAtAsc(OPEN);
    }

    /** Marks a request COMPLETED (erasable fields were erased, or none were erasable). */
    public ErasureRequest complete(UUID requestId, UUID operatorId, String note) {
        return resolve(requestId, ErasureRequestStatus.COMPLETED, operatorId, note);
    }

    /** Marks a request REJECTED (everything falls under a retention obligation). */
    public ErasureRequest reject(UUID requestId, UUID operatorId, String note) {
        return resolve(requestId, ErasureRequestStatus.REJECTED, operatorId, note);
    }

    private ErasureRequest resolve(UUID requestId, ErasureRequestStatus target, UUID operatorId, String note) {
        ErasureRequest req = repository.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("ErasureRequest", requestId));
        if (req.getStatus() == ErasureRequestStatus.COMPLETED || req.getStatus() == ErasureRequestStatus.REJECTED) {
            throw new IllegalStateException("Erasure request " + requestId + " is already resolved");
        }
        req.setStatus(target);
        req.setReviewedBy(operatorId);
        req.setReviewedAt(Instant.now());
        req.setResolutionNote(note);
        ErasureRequest saved = repository.save(req);

        Map<String, Object> details = new HashMap<>();
        details.put("erasureRequestId", requestId.toString());
        details.put("resolution", target.name());
        if (note != null && !note.isBlank()) details.put("note", note);
        eventPublisher.publishEvent(new DsarErasureResolvedEvent(req.getEntityId(), operatorId, "REGISTRY_ADMIN", details));
        log.info("DSAR erasure request {} resolved as {} by {}", requestId, target, operatorId);
        return saved;
    }
}
