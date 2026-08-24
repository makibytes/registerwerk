package de.makibytes.registerwerk.entra.web;

import java.time.Instant;

import de.makibytes.registerwerk.entra.api.EntraDirectoryException;
import de.makibytes.registerwerk.entra.api.EntraNotConfiguredException;
import de.makibytes.registerwerk.entra.api.EntraUnsupportedForIdentityModelException;
import de.makibytes.registerwerk.shared.api.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps this module's exceptions to HTTP responses.
 *
 * <p>Separate from {@code GlobalExceptionHandler} because {@code shared} is the dependency-free
 * foundation kernel — importing {@code entra.api} there would make {@code shared → entra} while
 * {@code entra → shared} already holds, which {@code ModulithArchitectureTest} rejects as a
 * cycle. Spring merges advices across the application, so the routing is identical either way.
 */
@RestControllerAdvice
class EntraExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(EntraExceptionAdvice.class);

    /**
     * The integration is switched off or incompletely configured. 503, not 500: the request was
     * valid, the capability simply is not wired up in this deployment.
     */
    @ExceptionHandler(EntraNotConfiguredException.class)
    ResponseEntity<ErrorResponse> handleNotConfigured(
            EntraNotConfiguredException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    /**
     * The operation cannot apply to this account because of how its identity is hosted — a
     * federated user, or an external guest who cannot hold a Temporary Access Pass. A category
     * error rather than a failure, so the message is passed through intact: during a support
     * call the operator needs to know what to do <em>instead</em>.
     */
    @ExceptionHandler(EntraUnsupportedForIdentityModelException.class)
    ResponseEntity<ErrorResponse> handleUnsupportedIdentityModel(
            EntraUnsupportedForIdentityModelException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /** A Microsoft Graph call failed. 502 — the fault is upstream, not in the request. */
    @ExceptionHandler(EntraDirectoryException.class)
    ResponseEntity<ErrorResponse> handleDirectoryFailure(
            EntraDirectoryException ex, HttpServletRequest request) {
        log.warn("Microsoft Graph call failed on {}: status={} code={}",
                request.getRequestURI(), ex.getHttpStatus(), ex.getGraphErrorCode());
        return build(HttpStatus.BAD_GATEWAY, ex.getMessage(), request);
    }

    private static ResponseEntity<ErrorResponse> build(
            HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                status.value(), message, Instant.now(), request.getRequestURI()));
    }
}
