package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.DualControlGate;
import de.makibytes.registerwerk.stepup.api.DualControlTarget;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import de.makibytes.registerwerk.stepup.events.DualControlApprovedEvent;
import de.makibytes.registerwerk.stepup.events.DualControlBootstrapUsedEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The single implementation of the 4-eyes check (K3, 6-08): validates the {@code X-Dual-Control-Token}
 * against the live request, consumes it once, and records {@link DualControlApprovedEvent}. Used by the
 * {@code @RequiresStepUp} aspect, the early approver interceptor (validate only) and, programmatically,
 * through {@link DualControlGate}.
 */
@Component
class DualControlService implements DualControlGate {

    private static final Logger log = LoggerFactory.getLogger(DualControlService.class);
    static final String DUAL_CONTROL_HEADER = DualControlApproverInterceptor.DUAL_CONTROL_HEADER;
    private static final int GATE_MAX_AGE_MINUTES = 10;

    private final StepUpTokenValidator validator;
    private final StepUpEnforcer enforcer;
    private final DualControlProperties properties;
    private final DualControlTokenUseRepository tokenUse;
    private final AppUserRepository users;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate tx;

    DualControlService(StepUpTokenValidator validator, StepUpEnforcer enforcer, DualControlProperties properties,
                       DualControlTokenUseRepository tokenUse, AppUserRepository users,
                       ApplicationEventPublisher eventPublisher, PlatformTransactionManager txManager) {
        this.validator = validator;
        this.enforcer = enforcer;
        this.properties = properties;
        this.tokenUse = tokenUse;
        this.users = users;
        this.eventPublisher = eventPublisher;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Validates only (no consumption); null when there is no usable token. Used by the early interceptor. */
    UUID peekApprover(HttpServletRequest request, Jwt initiator, String reason) {
        String token = request.getHeader(DUAL_CONTROL_HEADER);
        if (token == null || token.isBlank()) {
            return null;
        }
        return validator.validateDualControlToken(token, initiator.getSubject(), reason,
                expectedDigest(request, reason)).approverId();
    }

    /**
     * Validate, consume once and record - the check the aspect applies. The consumption and the audit
     * event commit together, so a failed audit write does not burn the approval and a successful one
     * cannot be replayed. A guarded action that fails afterwards does burn it (a fresh approval is
     * required), which is the deliberate fail-closed choice.
     */
    UUID approveAndConsume(Authentication auth, Jwt initiator, String reason, HttpServletRequest request) {
        String token = request != null ? request.getHeader(DUAL_CONTROL_HEADER) : null;
        if (token == null || token.isBlank()) {
            throw new AccessDeniedException(
                    "This action requires dual control: provide a second REGISTRY_ADMIN " +
                    "step-up token in the " + DUAL_CONTROL_HEADER + " header.");
        }
        StepUpTokenValidator.Approval approval =
                validator.validateDualControlToken(token, initiator.getSubject(), reason, expectedDigest(request, reason));
        UUID requestId = UUID.randomUUID();
        UUID initiatorId = SecurityUtils.extractUserId(auth);
        DualControlApprovedEvent event = new DualControlApprovedEvent(
                initiatorId, SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"),
                approval.approverId(), requestId, reason, request.getMethod(), request.getRequestURI(),
                enforcer.mode().name(), approval.jti(), approval.targetDigest());
        tx.executeWithoutResult(status -> {
            if (approval.jti() != null && !tokenUse.tryConsume(approval.jti(), approval.approverId(),
                    initiatorId != null ? initiatorId : UUID.fromString(initiator.getSubject()), reason,
                    approval.targetDigest(), approval.expiresAt())) {
                log.warn("Dual-control approval replay refused: approver={} action={} jti={}",
                        approval.approverId(), reason, approval.jti());
                throw new AccessDeniedException("Dual-control approval was already used. Obtain a new approval.");
            }
            // Published inside the transaction so the event-publication registry persists it with the consumption.
            eventPublisher.publishEvent(event);
        });
        request.setAttribute(StepUpAttributes.DUAL_CONTROL_APPROVER_ID, approval.approverId());
        request.setAttribute(StepUpAttributes.DUAL_CONTROL_REQUEST_ID, requestId);
        return approval.approverId();
    }

    @Override
    public UUID require(String reason) {
        HttpServletRequest request = currentRequest();
        Jwt jwt = currentJwt();
        enforcer.enforce(jwt, reason, GATE_MAX_AGE_MINUTES);
        return approveAndConsume(SecurityContextHolder.getContext().getAuthentication(), jwt, reason, request);
    }

    @Override
    public Outcome requireIfNotBootstrap(String reason) {
        HttpServletRequest request = currentRequest();
        Jwt jwt = currentJwt();
        enforcer.enforce(jwt, reason, GATE_MAX_AGE_MINUTES);
        long enrolled = users.countEnabledTotpEnrolledUsersWithRole(AppUserRole.REGISTRY_ADMIN);
        if (enrolled < 2) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            UUID requestId = UUID.randomUUID();
            tx.executeWithoutResult(status -> eventPublisher.publishEvent(new DualControlBootstrapUsedEvent(
                    SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"),
                    requestId, reason, request.getMethod(), request.getRequestURI(), enrolled)));
            request.setAttribute(StepUpAttributes.DUAL_CONTROL_REQUEST_ID, requestId);
            return new Outcome(null, true);
        }
        return new Outcome(approveAndConsume(auth(), jwt, reason, request), false);
    }

    private static Authentication auth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /**
     * The digest the approver's token must carry for this request, or null when target binding is rolled
     * back for the reason.
     */
    String expectedDigest(HttpServletRequest request, String reason) {
        if (!properties.bindsTarget(reason)) {
            return null;
        }
        String canonicalBody = null;
        if (properties.bindsBody(reason)) {
            Object cached = request.getAttribute(DualControlBodyCachingFilter.CACHED_BODY_ATTRIBUTE);
            if (!(cached instanceof byte[] bytes)) {
                throw new AccessDeniedException(
                        "This approval is bound to the request body, which is unavailable (body too large?).");
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            try {
                canonicalBody = json.isBlank() ? "" : DualControlTarget.canonicalJson(json);
            } catch (IllegalArgumentException e) {
                throw new AccessDeniedException("The request body is not valid JSON.");
            }
        }
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
        return DualControlTarget.digest(request.getMethod(), path, request.getQueryString(), canonicalBody);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        throw new AccessDeniedException("Dual control requires an HTTP request.");
    }

    private static Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw new AccessDeniedException("Step-up auth requires a valid JWT.");
    }
}
