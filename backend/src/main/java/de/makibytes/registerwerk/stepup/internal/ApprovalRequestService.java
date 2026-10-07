package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.stepup.api.DualControlTarget;
import de.makibytes.registerwerk.stepup.events.ApprovalRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The in-app approval queue (T8-02). It replaces "copy the approval request to a colleague and have them mint a
 * token in their own session" with a server-side state machine, without weakening any of what C1/C2 established:
 *
 * <pre>
 * PENDING --approve--> APPROVED --claim--> CLAIMED      (terminal; the token has been handed out once)
 *    |  \--reject--> REJECTED                  
 *    +-- cancel / expiry --> CANCELLED / EXPIRED
 * </pre>
 *
 * <ul>
 *   <li>The request is digested with {@link DualControlTarget} (the function the real endpoint's validator uses),
 *       so the token minted at claim time is bound to exactly that method, path, query and canonical body.</li>
 *   <li>The approver is never the requester (checked, enforced in the {@code UPDATE}, and by a table constraint),
 *       must be a currently enabled REGISTRY_ADMIN / COMPLIANCE_OFFICER, and proves a fresh TOTP code (and, in
 *       Entra mode, the Conditional Access context) when approving.</li>
 *   <li>Only the requester can claim, once; the approver's eligibility is checked again at that moment. The token is
 *       an ordinary approver token (marker, audience, scope, target digest, single-use jti) additionally bound to
 *       the requester, so it is useless in anyone else's hands.</li>
 *   <li>Every transition is one conditional {@code UPDATE}; concurrent callers resolve to exactly one winner.</li>
 * </ul>
 *
 * Methods hold no transaction while verifying a TOTP code (the verification takes its own connection).
 */
@Component
public class ApprovalRequestService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRequestService.class);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final String API_PREFIX = "/api/v1/";
    private static final int MAX_NOTE = 1000;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int STEP_UP_MAX_AGE_MINUTES = 10;

    /** The authenticated caller; {@code jwt} is their session token (used for the Entra proof only). */
    public record Caller(UUID userId, String role, Jwt jwt) {}

    /** What an initiator asks to have approved. */
    public record Draft(String action, String method, String path, String query, JsonNode body) {}

    /** The one-time result of a claim. */
    public record Claim(String approvalToken, Instant expiresAt, String action, String target, UUID requestId) {}

    /** A page of records. */
    public record Slice(List<ApprovalRequestRecord> content, long total, int page, int size) {}

    private final ApprovalRequestRepository repository;
    private final ApprovalActionCatalog catalog;
    private final ApprovalQueueProperties properties;
    private final DualControlProperties dualControl;
    private final StepUpTokenIssuer issuer;
    private final StepUpEnforcer enforcer;
    private final AppUserRepository users;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    ApprovalRequestService(ApprovalRequestRepository repository, ApprovalActionCatalog catalog,
                           ApprovalQueueProperties properties, DualControlProperties dualControl,
                           StepUpTokenIssuer issuer, StepUpEnforcer enforcer, AppUserRepository users,
                           ApplicationEventPublisher events, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.catalog = catalog;
        this.properties = properties;
        this.dualControl = dualControl;
        this.issuer = issuer;
        this.enforcer = enforcer;
        this.users = users;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
    }

    // ── create ───────────────────────────────────────────────────────────────

    public ApprovalRequestRecord create(Caller caller, Draft draft) {
        String action = require(draft.action(), "action", 160);
        String method = require(draft.method(), "method", 8).toUpperCase(Locale.ROOT);
        String path = require(draft.path(), "path", 500);
        String query = draft.query() == null || draft.query().isEmpty() ? null : draft.query();
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("method must be one of " + METHODS);
        }
        if (!path.startsWith(API_PREFIX) || path.chars().anyMatch(c -> c <= ' ' || c == '?' || c == '#' || c == 127)
                || path.contains("..")) {
            throw new IllegalArgumentException("path must be a request path below " + API_PREFIX + " without query or fragment");
        }
        if (query != null && (query.length() > 2000 || query.startsWith("?") || query.chars().anyMatch(c -> c <= ' ' || c == '#'))) {
            throw new IllegalArgumentException("query must be the raw query string without '?' and fragment");
        }
        if (!catalog.accepts(action, method, path)) {
            throw new IllegalArgumentException("Unknown action for this request: '" + action
                    + "' is not a four-eyes action of " + method + " " + path);
        }

        String canonicalBody = null;
        if (dualControl.bindsBody(action)) {
            boolean hasBody = draft.body() != null && !draft.body().isNull();
            canonicalBody = hasBody ? canonicalOf(draft.body()) : "";
            if (canonicalBody.getBytes(StandardCharsets.UTF_8).length > properties.getMaxBodyBytes()) {
                throw new IllegalArgumentException("The request body is too large for an approval request (max "
                        + properties.getMaxBodyBytes() + " bytes).");
            }
        } else if (draft.body() != null && !draft.body().isNull()) {
            // Secret-bearing or non-JSON payloads are never copied into a request that travels to a second person.
            throw new IllegalArgumentException("The body of '" + action + "' is not part of an approval; do not send it.");
        }
        String digest;
        try {
            digest = DualControlTarget.digest(method, path, query, canonicalBody);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The request cannot be bound to an approval: " + e.getMessage());
        }
        if (repository.countOpen(caller.userId()) >= properties.getMaxOpenPerRequester()) {
            throw new InvalidStateTransitionException("Too many open approval requests; cancel or wait for some to finish.");
        }

        UUID id = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(properties.getTtl());
        String storedBody = canonicalBody;
        tx.executeWithoutResult(s -> {
            repository.insert(id, caller.userId(), action, method, path, query, storedBody, digest, expiresAt);
            events.publishEvent(new ApprovalRequestEvent("CREATED", id, caller.userId(), caller.role(), caller.userId(),
                    null, action, method, path, digest, null, null));
        });
        return repository.find(id).orElseThrow();
    }

    private static String canonicalOf(JsonNode body) {
        try {
            return DualControlTarget.canonicalJson(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The request body cannot be bound to an approval: " + e.getMessage());
        }
    }

    // ── read ─────────────────────────────────────────────────────────────────

    public Slice pending(Caller caller, int page, int size) {
        requireEligibleApprover(caller.userId());
        int s = clampSize(size);
        int p = Math.max(page, 0);
        return new Slice(repository.pending(caller.userId(), s, (long) p * s), repository.countPending(caller.userId()), p, s);
    }

    public long pendingCount(Caller caller) {
        requireEligibleApprover(caller.userId());
        return repository.countPending(caller.userId());
    }

    public Slice mine(Caller caller, int page, int size) {
        int s = clampSize(size);
        int p = Math.max(page, 0);
        return new Slice(repository.mine(caller.userId(), s, (long) p * s), repository.countMine(caller.userId()), p, s);
    }

    /** The requester, or an eligible approver; anyone else gets the same 404 as for an id that does not exist. */
    public ApprovalRequestRecord get(Caller caller, UUID id) {
        ApprovalRequestRecord r = repository.find(id).orElseThrow(() -> new EntityNotFoundException("ApprovalRequest", id));
        boolean requester = r.requesterUserId().equals(caller.userId());
        if (!requester && !StepUpTokenValidator.isEligibleApprover(users.findById(caller.userId()).orElse(null))) {
            throw new EntityNotFoundException("ApprovalRequest", id);
        }
        return r;
    }

    // ── decide ───────────────────────────────────────────────────────────────

    public ApprovalRequestRecord approve(Caller caller, UUID id, String code, String note) {
        ApprovalRequestRecord r = loadDecidable(caller, id);
        // Entra mode: the approver's own session must carry the Conditional Access context, as on any
        // @RequiresStepUp endpoint (a claims challenge when it does not). Local mode proves the factor by the code.
        if (enforcer.mode() == StepUpMode.ENTRA_AUTH_CONTEXT) {
            enforcer.enforce(caller.jwt(), r.action(), STEP_UP_MAX_AGE_MINUTES);
        }
        issuer.verifySecondFactor(caller.userId(), code);
        String cleanNote = clean(note);
        boolean won = Boolean.TRUE.equals(tx.execute(s -> {
            if (!repository.decide(id, caller.userId(), ApprovalRequestRecord.APPROVED, cleanNote)) {
                return false;
            }
            events.publishEvent(new ApprovalRequestEvent("APPROVED", id, caller.userId(), caller.role(),
                    r.requesterUserId(), caller.userId(), r.action(), r.method(), r.path(), r.targetDigest(),
                    cleanNote, null));
            return true;
        }));
        if (!won) {
            throw lost(id, "decided");
        }
        return repository.find(id).orElseThrow();
    }

    public ApprovalRequestRecord reject(Caller caller, UUID id, String note) {
        ApprovalRequestRecord r = loadDecidable(caller, id);
        String cleanNote = clean(note);
        boolean won = Boolean.TRUE.equals(tx.execute(s -> {
            if (!repository.decide(id, caller.userId(), ApprovalRequestRecord.REJECTED, cleanNote)) {
                return false;
            }
            events.publishEvent(new ApprovalRequestEvent("REJECTED", id, caller.userId(), caller.role(),
                    r.requesterUserId(), caller.userId(), r.action(), r.method(), r.path(), r.targetDigest(),
                    cleanNote, null));
            return true;
        }));
        if (!won) {
            throw lost(id, "decided");
        }
        return repository.find(id).orElseThrow();
    }

    /** The request, if the caller may decide it now: eligible, not the requester, PENDING and not expired. */
    private ApprovalRequestRecord loadDecidable(Caller caller, UUID id) {
        requireEligibleApprover(caller.userId());
        ApprovalRequestRecord r = repository.find(id).orElseThrow(() -> new EntityNotFoundException("ApprovalRequest", id));
        if (r.requesterUserId().equals(caller.userId())) {
            log.warn("Self-approval refused: user={} request={} action={}", caller.userId(), id, r.action());
            throw new AccessDeniedException("Dual control: a request cannot be decided by the person who made it.");
        }
        requireOpen(r, ApprovalRequestRecord.PENDING);
        return r;
    }

    // ── cancel / claim ───────────────────────────────────────────────────────

    public ApprovalRequestRecord cancel(Caller caller, UUID id) {
        ApprovalRequestRecord r = loadOwn(caller, id);
        boolean won = Boolean.TRUE.equals(tx.execute(s -> {
            if (!repository.cancel(id, caller.userId())) {
                return false;
            }
            events.publishEvent(new ApprovalRequestEvent("CANCELLED", id, caller.userId(), caller.role(),
                    r.requesterUserId(), r.approverUserId(), r.action(), r.method(), r.path(), r.targetDigest(),
                    null, null));
            return true;
        }));
        if (!won) {
            throw lost(id, "cancelled");
        }
        return repository.find(id).orElseThrow();
    }

    /**
     * Hands the requester the approver token for an APPROVED request - once. The approver must still be an
     * enabled, eligible approver now; the status flip and the audit entry commit together with the minting, so a
     * failure leaves the request APPROVED and claimable.
     */
    public Claim claim(Caller caller, UUID id) {
        ApprovalRequestRecord r = loadOwn(caller, id);
        requireOpen(r, ApprovalRequestRecord.APPROVED);
        AppUser approver = users.findById(r.approverUserId()).orElse(null);
        if (!StepUpTokenValidator.isEligibleApprover(approver) || approver.getId().equals(caller.userId())) {
            log.warn("Approval claim refused, approver no longer eligible: request={} approver={}", id, r.approverUserId());
            throw new AccessDeniedException("The approver is no longer an enabled REGISTRY_ADMIN or COMPLIANCE_OFFICER. "
                    + "Make a new request.");
        }
        String jti = JwtMintingService.newJti();
        StepUpTokenIssuer.MintedApproval minted = tx.execute(s -> {
            if (!repository.claim(id, caller.userId(), jti)) {
                return null;
            }
            StepUpTokenIssuer.MintedApproval m = issuer.mintApprovalToken(approver, r.action(), r.targetDigest(),
                    caller.userId(), id, jti);
            events.publishEvent(new ApprovalRequestEvent("CLAIMED", id, caller.userId(), caller.role(),
                    r.requesterUserId(), r.approverUserId(), r.action(), r.method(), r.path(), r.targetDigest(),
                    null, jti));
            return m;
        });
        if (minted == null) {
            throw lost(id, "claimed");
        }
        return new Claim(minted.token(), minted.expiresAt(), r.action(), r.target(), id);
    }

    private ApprovalRequestRecord loadOwn(Caller caller, UUID id) {
        ApprovalRequestRecord r = repository.find(id).orElseThrow(() -> new EntityNotFoundException("ApprovalRequest", id));
        if (!r.requesterUserId().equals(caller.userId())) {
            // Ids are random UUIDs; naming the missing right is more useful than a 404 here.
            throw new AccessDeniedException("Only the person who made a request can do this to it.");
        }
        return r;
    }

    // ── expiry ───────────────────────────────────────────────────────────────

    /** Marks stale PENDING/APPROVED requests EXPIRED, with an audit entry each. @return how many */
    public int expireStale() {
        List<ApprovalRequestRepository.Expired> expired = tx.execute(s -> {
            List<ApprovalRequestRepository.Expired> rows = repository.expireStale();
            for (ApprovalRequestRepository.Expired e : rows) {
                events.publishEvent(new ApprovalRequestEvent("EXPIRED", e.id(), null, "SYSTEM", e.requesterUserId(),
                        e.approverUserId(), e.action(), e.method(), e.path(), e.targetDigest(),
                        "expired while " + e.previousStatus(), null));
            }
            return rows;
        });
        return expired == null ? 0 : expired.size();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void requireEligibleApprover(UUID userId) {
        if (!StepUpTokenValidator.isEligibleApprover(users.findById(userId).orElse(null))) {
            throw new AccessDeniedException("Only an enabled REGISTRY_ADMIN or COMPLIANCE_OFFICER can decide approval requests.");
        }
    }

    private static void requireOpen(ApprovalRequestRecord r, String expectedStatus) {
        if (!expectedStatus.equals(r.status())) {
            throw new InvalidStateTransitionException("Approval request is " + r.status() + ", not " + expectedStatus + ".");
        }
        if (!r.expiresAt().isAfter(Instant.now())) {
            throw new InvalidStateTransitionException("Approval request has expired.");
        }
    }

    /** The conditional UPDATE matched nothing: somebody else got there first, or the request just expired. */
    private InvalidStateTransitionException lost(UUID id, String what) {
        String now = repository.find(id).map(ApprovalRequestRecord::status).orElse("unknown");
        return new InvalidStateTransitionException("Approval request was not " + what + ": it is now " + now + ".");
    }

    private static String clean(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String t = note.strip();
        return t.length() > MAX_NOTE ? t.substring(0, MAX_NOTE) : t;
    }

    private static String require(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " is required (max " + max + " characters)");
        }
        return value.strip();
    }

    private static int clampSize(int size) {
        return size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    }
}
