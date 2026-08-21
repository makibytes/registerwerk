package de.makibytes.registerwerk.finality.web;

import de.makibytes.registerwerk.finality.api.FinalityDecision;
import de.makibytes.registerwerk.finality.api.FinalityNotReachedException;
import de.makibytes.registerwerk.finality.web.dto.FinalityErrorResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.shared.events.RejectedActionEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Set;

/**
 * Separate {@code @RestControllerAdvice} from {@code shared.web.GlobalExceptionHandler} — see
 * {@link FinalityNotReachedException}'s javadoc for why: {@code shared} must never import {@code
 * finality}, and Spring dispatches an exception to whichever registered advice declares the most
 * specific matching {@code @ExceptionHandler} across all advice beans, so this one wins over
 * {@code shared}'s generic {@code ComplianceGateException} handler for {@link
 * FinalityNotReachedException} without any explicit ordering.
 *
 * <p>Duplicates {@code GlobalExceptionHandler.handleComplianceGate}'s rejected-action audit
 * recording rather than sharing it (that method is private) — same {@link RejectedActionEvent},
 * same mutating-methods-only / route-template-not-raw-URI reasoning.
 */
@RestControllerAdvice
public class FinalityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FinalityExceptionHandler.class);
    private static final Set<String> MUTATING_METHODS = Set.of(
            HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.PATCH.name(), HttpMethod.DELETE.name());

    private final ApplicationEventPublisher eventPublisher;

    public FinalityExceptionHandler(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @ExceptionHandler(FinalityNotReachedException.class)
    public ResponseEntity<FinalityErrorResponse> handleFinalityNotReached(
            FinalityNotReachedException ex, HttpServletRequest request) {
        recordRejectionIfMutating(ex.getMessage(), request);

        FinalityDecision.Blocked decision = ex.decision();
        FinalityErrorResponse body = new FinalityErrorResponse(
                HttpStatus.CONFLICT.value(), ex.getMessage(), Instant.now(), routeTemplate(request),
                decision.operation().name(), decision.requiredLevel().name(),
                decision.currentLevel().name(), decision.reason().name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private void recordRejectionIfMutating(String reason, HttpServletRequest request) {
        if (!MUTATING_METHODS.contains(request.getMethod())) return;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            eventPublisher.publishEvent(new RejectedActionEvent("FINALITY_NOT_REACHED", routeTemplate(request),
                    request.getMethod(), reason, SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, null)));
        } catch (Exception e) {
            log.warn("Failed to record rejected-action audit entry for {} {}: {}",
                    request.getMethod(), request.getRequestURI(), e.getMessage());
        }
    }

    private static String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }
}
