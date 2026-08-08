package de.makibytes.registerwerk.shared.web;

import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidCredentialsException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.shared.LoginDisabledException;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.shared.api.ErrorResponse;
import de.makibytes.registerwerk.shared.events.RejectedActionEvent;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Centralised exception handler that maps domain and framework exceptions to HTTP error responses.
 *
 * <p>Mutating requests (POST/PUT/PATCH/DELETE) that are blocked by an access-denial
 * (including step-up/4-eyes rejections — {@code StepUpEnforcementAspect} throws the same
 * {@link AccessDeniedException}) or by an invalid state transition also publish a
 * {@link RejectedActionEvent}, which the {@code audit} module turns into an
 * {@code audit_event} row — so "someone tried a wrongful action and was stopped" is
 * auditable, not just SLF4J-logged. GET requests are not recorded to avoid flooding the
 * audit log with routine read-access probes. A plain event (not {@code AuditableEvent}) is
 * used deliberately: {@code shared} is the dependency-free foundation kernel and must not
 * import the {@code audit} module.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Set<String> MUTATING_METHODS = Set.of(
            HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.PATCH.name(), HttpMethod.DELETE.name());

    private final ApplicationEventPublisher eventPublisher;

    public GlobalExceptionHandler(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EntityNotFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(LoginDisabledException.class)
    public ResponseEntity<ErrorResponse> handleLoginDisabled(LoginDisabledException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(InvalidStateTransitionException ex, HttpServletRequest request) {
        recordRejectionIfMutating("INVALID_STATE_TRANSITION", ex.getMessage(), request);
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI());
    }

    /**
     * Compliance-gate rejections (KYC status, unresolved sanctions hits, Sperrvermerk,
     * Travel Rule/MiCA fail-closed) — a {@link ComplianceGateException} is always also an
     * {@link IllegalStateException}, so this maps to the same 409, but is additionally
     * recorded as a rejected action (unlike the generic handler below), since these
     * specifically represent "someone tried a forbidden action," not a system error.
     */
    @ExceptionHandler(ComplianceGateException.class)
    public ResponseEntity<ErrorResponse> handleComplianceGate(ComplianceGateException ex, HttpServletRequest request) {
        recordRejectionIfMutating("COMPLIANCE_GATE_BLOCKED", ex.getMessage(), request);
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI());
    }

    /**
     * Other business-state rejections map to 409 — the request was valid but the current
     * state forbids the operation. Deliberately NOT recorded as a rejected action: this
     * catch-all also covers unrelated config/infra {@link IllegalStateException}s thrown
     * throughout the codebase, and blanket-recording all of those would flood the audit log
     * with system errors rather than genuine forbidden-action attempts (see
     * {@link ComplianceGateException} for the narrower, recorded subtype).
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    /**
     * A concurrent modification lost the optimistic-lock race (e.g. two writes to the
     * same holder's nominal amount). The request was valid; the caller should re-read
     * and retry. Covers both Spring's {@link OptimisticLockingFailureException} and the
     * JPA {@code jakarta.persistence.OptimisticLockException} it wraps.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class})
    public ResponseEntity<ErrorResponse> handleOptimisticLock(Exception ex, HttpServletRequest request) {
        log.warn("Optimistic lock conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT,
                "The record was modified concurrently. Please re-read and retry.", request.getRequestURI());
    }

    /**
     * A unique/foreign-key constraint was violated (e.g. re-registering a wallet that collides
     * with an active row under a partial-unique index). The request was valid; the caller should
     * inspect current state rather than retry unchanged.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT,
                "The request conflicts with existing data.", request.getRequestURI());
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ErrorResponse> handleUnsupported(UnsupportedOperationException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        recordRejectionIfMutating("ACCESS_DENIED", ex.getMessage(), request);
        return buildResponse(HttpStatus.FORBIDDEN, "Access denied", request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation failed: " + message, request.getRequestURI());
    }

    /** Invalid JSON, an incompatible JSON value, or a missing request body is a client error. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Unreadable request body on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "Request body is missing or malformed", request.getRequestURI());
    }

    /** Covers invalid UUIDs/enums/numbers supplied as path variables or query parameters. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST,
                "Invalid value for parameter '" + ex.getName() + "'", request.getRequestURI());
    }

    /** Covers constraints declared directly on controller method parameters and service methods. */
    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> handleConstraintViolation(Exception ex, HttpServletRequest request) {
        log.debug("Request validation failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "Request validation failed", request.getRequestURI());
    }

    /** Missing headers, cookies, or required request parameters must not surface as HTTP 500. */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ErrorResponse> handleRequestBinding(
            ServletRequestBindingException ex, HttpServletRequest request) {
        log.debug("Request binding failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST,
                "Required request data is missing or invalid", request.getRequestURI());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.METHOD_NOT_ALLOWED,
                "HTTP method " + request.getMethod() + " is not supported for this endpoint",
                request.getRequestURI());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Request content type is not supported", request.getRequestURI());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_ACCEPTABLE,
                "No acceptable response representation is available", request.getRequestURI());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, "No endpoint found for " + request.getMethod() + " " + request.getRequestURI(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String message, String path) {
        ErrorResponse body = new ErrorResponse(status.value(), message, Instant.now(), path);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Best-effort: a failure recording a rejection must never mask the original error
     * response to the caller (nor a bug in the recorder crash unrelated requests).
     */
    private void recordRejectionIfMutating(String eventType, String reason, HttpServletRequest request) {
        if (!MUTATING_METHODS.contains(request.getMethod())) return;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            eventPublisher.publishEvent(new RejectedActionEvent(eventType, routeTemplate(request),
                    request.getMethod(), reason, SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, null)));
        } catch (Exception e) {
            log.warn("Failed to record rejected-action audit entry for {} {}: {}",
                    request.getMethod(), request.getRequestURI(), e.getMessage());
        }
    }

    /**
     * Prefers the matched route PATTERN (e.g. {@code /assets/{assetId}/deployments/{depId}/
     * admin/force-burn}) over the resolved URI: {@link AuditFailureRecorder} hashes this value
     * into {@code subject_id} to group rejections by endpoint, which the raw URI defeats — it
     * contains real path-variable values (an asset/deployment UUID), so every resource would
     * get its own subject_id instead of sharing one per endpoint. Spring populates this
     * attribute during handler mapping, before the controller method (and thus before any
     * exception this handler reacts to) runs; falls back to the raw URI if unavailable (e.g.
     * a request that never matched a route).
     */
    private static String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }

}
