package de.makibytes.registerwerk.stepup.web;

import de.makibytes.registerwerk.idempotency.api.NoIdempotencyReplay;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.shared.api.PageResponse;
import de.makibytes.registerwerk.stepup.internal.ApprovalRequestRecord;
import de.makibytes.registerwerk.stepup.internal.ApprovalRequestService;
import de.makibytes.registerwerk.stepup.web.dto.ApprovalClaimResponse;
import de.makibytes.registerwerk.stepup.web.dto.ApprovalDecisionRequest;
import de.makibytes.registerwerk.stepup.web.dto.ApprovalPendingCount;
import de.makibytes.registerwerk.stepup.web.dto.ApprovalRequestView;
import de.makibytes.registerwerk.stepup.web.dto.CreateApprovalRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The in-app approval queue (T8-02), for both portals. An initiator files a request, an eligible approver
 * (REGISTRY_ADMIN / COMPLIANCE_OFFICER, never the requester) approves or rejects it with a fresh TOTP code, and the
 * initiator claims a single-use approver token bound to exactly that request, to send as
 * {@code X-Dual-Control-Token}. It adds a way to obtain the approval; the guarded endpoints enforce exactly what
 * they enforced before. The older copy-the-request flow ({@code POST /api/v1/auth/step-up} with action/target)
 * keeps working.
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private static final String APPROVER_ROLES = "hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER')";

    private final ApprovalRequestService service;

    ApprovalController(ApprovalRequestService service) {
        this.service = service;
    }

    /** Files a request; the server canonicalises and digests it. 400 for an action/route that is not a four-eyes one. */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApprovalRequestView> create(Authentication auth, @RequestBody @Valid CreateApprovalRequest body) {
        ApprovalRequestRecord r = service.create(caller(auth), new ApprovalRequestService.Draft(
                body.action(), body.method(), body.path(), body.query(), body.body()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApprovalRequestView.from(r));
    }

    /** Open requests of other people, oldest first. */
    @GetMapping("/pending")
    @PreAuthorize(APPROVER_ROLES)
    public PageResponse<ApprovalRequestView> pending(Authentication auth,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return page(service.pending(caller(auth), page, size));
    }

    @GetMapping("/pending/count")
    @PreAuthorize(APPROVER_ROLES)
    public ApprovalPendingCount pendingCount(Authentication auth) {
        return new ApprovalPendingCount(service.pendingCount(caller(auth)));
    }

    /** The caller's own requests, newest first, in every status. */
    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public PageResponse<ApprovalRequestView> mine(Authentication auth,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return page(service.mine(caller(auth), page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApprovalRequestView get(Authentication auth, @PathVariable UUID id) {
        return ApprovalRequestView.from(service.get(caller(auth), id));
    }

    /** Approver only; needs the approver's fresh TOTP code in {@code code}. */
    @NoIdempotencyReplay
    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVER_ROLES)
    public ApprovalRequestView approve(Authentication auth, @PathVariable UUID id,
                                       @RequestBody @Valid ApprovalDecisionRequest body) {
        return ApprovalRequestView.from(service.approve(caller(auth), id, body.code(), body.note()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(APPROVER_ROLES)
    public ApprovalRequestView reject(Authentication auth, @PathVariable UUID id,
                                      @RequestBody(required = false) @Valid ApprovalDecisionRequest body) {
        return ApprovalRequestView.from(service.reject(caller(auth), id, body == null ? null : body.note()));
    }

    /** Requester only, while PENDING or APPROVED (not yet claimed). */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ApprovalRequestView cancel(Authentication auth, @PathVariable UUID id) {
        return ApprovalRequestView.from(service.cancel(caller(auth), id));
    }

    /** Requester only, once, for an APPROVED request: returns the bound approver token. Never replayed from cache. */
    @NoIdempotencyReplay
    @PostMapping("/{id}/claim")
    @PreAuthorize("isAuthenticated()")
    public ApprovalClaimResponse claim(Authentication auth, @PathVariable UUID id) {
        ApprovalRequestService.Claim c = service.claim(caller(auth), id);
        return new ApprovalClaimResponse(c.requestId(), c.approvalToken(), c.expiresAt(), c.action(), c.target(),
                "X-Dual-Control-Token");
    }

    private static PageResponse<ApprovalRequestView> page(ApprovalRequestService.Slice s) {
        List<ApprovalRequestView> content = s.content().stream().map(ApprovalRequestView::from).toList();
        int pages = s.size() == 0 ? 0 : (int) ((s.total() + s.size() - 1) / s.size());
        return new PageResponse<>(content, s.total(), pages, s.page(), s.size());
    }

    /** The caller; an impersonation session acts for someone else and has no standing in a four-eyes queue. */
    private static ApprovalRequestService.Caller caller(Authentication auth) {
        UUID userId = SecurityUtils.extractUserId(auth);
        if (userId == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new AccessDeniedException("An authenticated user is required.");
        }
        if (SecurityUtils.isImpersonatingAdmin(auth)) {
            throw new AccessDeniedException("The approval queue is not available while impersonating.");
        }
        return new ApprovalRequestService.Caller(userId, SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), jwt);
    }
}
