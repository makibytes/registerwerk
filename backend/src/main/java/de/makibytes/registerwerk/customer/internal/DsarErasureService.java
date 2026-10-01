package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.ActiveHoldingsPort;
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
    private final AppUserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ActiveHoldingsPort activeHoldingsPort;

    public DsarErasureService(ErasureRequestRepository repository, AppUserRepository userRepository,
                               ApplicationEventPublisher eventPublisher, ActiveHoldingsPort activeHoldingsPort) {
        this.activeHoldingsPort = activeHoldingsPort;
        this.repository = repository;
        this.userRepository = userRepository;
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

    /**
     * Marks a request COMPLETED and actually tombstones the erasable personal-data fields —
     * previously {@code complete} only flipped the status; no field was ever erased or
     * anonymized, which is not what Art. 17 erasure means.
     *
     * <p>Scope is deliberately narrow: only the natural-person contact fields on each
     * {@code AppUser} tied to the entity (full name, email, password hash) are tombstoned, and
     * the users are disabled. {@code LegalEntity}'s own registration data (LEI, registration
     * number, entity number, incorporation date) is NOT erased — eWpG/GwG retention obligations
     * require keeping the register and KYC/AML trail; DSGVO Art. 17(3)(b) exempts erasure where
     * processing is necessary for compliance with a legal obligation. This is exactly the
     * "weighed against statutory retention" judgment the operator note field already existed to
     * document — this method makes the KEPT/ERASED split concrete rather than leaving both
     * fully intact.
     */
    public ErasureRequest complete(UUID requestId, UUID operatorId, String note, UUID approverId) {
        return complete(requestId, operatorId, note, approverId, null);
    }

    /**
     * T3-14: while the entity still has active register holdings the operator must name the notice
     * channel that is retained for the statutory §19 eWpG notices (the tombstoned contact data can no
     * longer serve as one); the choice is recorded on the request and in the audit event. Completion
     * is not otherwise blocked - the retention-channel policy itself is a parked decision.
     */
    public ErasureRequest complete(UUID requestId, UUID operatorId, String note, UUID approverId,
                                   String retainedNoticeChannel) {
        ErasureRequest req = repository.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("ErasureRequest", requestId));
        if (req.getStatus() == ErasureRequestStatus.COMPLETED || req.getStatus() == ErasureRequestStatus.REJECTED) {
            // Guard BEFORE tombstoning anything — resolve() re-checks this too, but only after
            // the loop below would already have run; an already-resolved request (in particular
            // one already REJECTED, i.e. explicitly NOT to be erased) must not be tombstoned as
            // a side effect of a call that is about to fail anyway.
            throw new IllegalStateException("Erasure request " + requestId + " is already resolved");
        }
        boolean hasHoldings = activeHoldingsPort.hasActiveHoldings(req.getEntityId());
        if (hasHoldings && (retainedNoticeChannel == null || retainedNoticeChannel.isBlank())) {
            throw new IllegalArgumentException("The entity still has active register holdings: state the retained "
                    + "notice channel (retainedNoticeChannel) that serves the statutory §19 eWpG notices after erasure.");
        }
        if (retainedNoticeChannel != null && !retainedNoticeChannel.isBlank()) {
            req.setRetainedNoticeChannel(retainedNoticeChannel.trim());
        }
        int erasedCount = 0;
        for (AppUser user : userRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(req.getEntityId())) {
            if (isAlreadyErased(user)) {
                continue;
            }
            user.setFullName("[ERASED]");
            user.setEmail("erased-" + user.getId() + "@erased.invalid");
            user.setPasswordHash(null);
            user.setEnabled(false);
            userRepository.save(user);
            erasedCount++;
        }
        log.warn("DSAR erasure {} tombstoned {} AppUser(s) for entityId={}", requestId, erasedCount, req.getEntityId());
        // Honest outcome (6-30): this routine only erases AppUser contact data. Everything else is listed,
        // never silently implied to be erased; no purge sweep exists (retention decision T6-10 is parked).
        req.setResolutionDetail(coverageJson(erasedCount));
        return resolve(requestId, ErasureRequestStatus.COMPLETED_PARTIAL, operatorId, note, approverId);
    }

    static final List<String> RETAINED = List.of(
            "LegalEntity registration data (LEI, registration number, entity number): eWpG s.15(3) 10 y / GwG s.8 5 y, Art. 17(3)(b) DSGVO",
            "register holdings and transfer history: eWpG retention",
            "audit log: Art. 17(3)(b) DSGVO legal obligation");
    static final List<String> NOT_COVERED = List.of(
            "natural persons linked to the entity (beneficial owners, directors) and their KYC documents",
            "screening runs and hits",
            "support tickets and trade notes",
            "webhook subscriptions and deliveries",
            "Travel Rule / IVMS data",
            "backups and exports already delivered");

    private static String coverageJson(int erasedUsers) {
        java.util.function.Function<List<String>, String> arr = l -> "[" + l.stream()
                .map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]";
        return "{\"erased\":" + arr.apply(List.of("AppUser full name, e-mail and password hash tombstoned (" + erasedUsers
                + " user(s) newly erased); users disabled")) + ",\"retained\":" + arr.apply(RETAINED)
                + ",\"notCovered\":" + arr.apply(NOT_COVERED) + "}";
    }

    private static boolean isAlreadyErased(AppUser user) {
        return user.getEmail() != null && user.getEmail().endsWith("@erased.invalid");
    }

    /** Marks a request REJECTED (everything falls under a retention obligation). */
    public ErasureRequest reject(UUID requestId, UUID operatorId, String note) {
        return resolve(requestId, ErasureRequestStatus.REJECTED, operatorId, note, null);
    }

    private ErasureRequest resolve(UUID requestId, ErasureRequestStatus target, UUID operatorId, String note, UUID approverId) {
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
        if (req.getResolutionDetail() != null) details.put("coverage", req.getResolutionDetail());
        if (req.getRetainedNoticeChannel() != null) details.put("retainedNoticeChannel", req.getRetainedNoticeChannel());
        eventPublisher.publishEvent(
                new DsarErasureResolvedEvent(req.getEntityId(), operatorId, "REGISTRY_ADMIN", approverId, details));
        log.info("DSAR erasure request {} resolved as {} by {}", requestId, target, operatorId);
        return saved;
    }
}
