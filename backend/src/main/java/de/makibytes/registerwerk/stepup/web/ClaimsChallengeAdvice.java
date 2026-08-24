package de.makibytes.registerwerk.stepup.web;

import de.makibytes.registerwerk.stepup.api.ClaimsChallengeException;
import de.makibytes.registerwerk.stepup.web.dto.ClaimsChallengeResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a {@link ClaimsChallengeException} into the OAuth2 claims challenge Entra and MSAL
 * expect.
 *
 * <p><strong>Why an advice and not an {@code AccessDeniedHandler}.</strong> The exception is
 * thrown from an AOP {@code @Around} aspect wrapping the controller method, so it propagates to
 * {@code DispatcherServlet} and is resolved by {@code @RestControllerAdvice}. Spring Security's
 * exception-translation filter sits further out and never sees it. And even if it did,
 * {@code BearerTokenAuthenticationEntryPoint} can only serialise {@code realm},
 * {@code error}, {@code error_description}, {@code error_uri} and {@code scope} — it has no code
 * path that emits {@code claims=} or {@code authorization_uri=}. The header has to be written here.
 *
 * <p>{@code HIGHEST_PRECEDENCE} so this is consulted ahead of the unordered
 * {@code GlobalExceptionHandler}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ClaimsChallengeAdvice {

    private static final Logger log = LoggerFactory.getLogger(ClaimsChallengeAdvice.class);

    @ExceptionHandler(ClaimsChallengeException.class)
    ResponseEntity<ClaimsChallengeResponse> handle(ClaimsChallengeException ex, HttpServletRequest request) {
        // RFC 7235 allows each auth-param name only once per scheme, and all of them must sit in
        // a single header. realm must be empty when authorization_uri points at /common.
        String challenge = "Bearer realm=\"\""
                + ", authorization_uri=\"" + ex.getAuthorizationUri() + "\""
                + ", error=\"insufficient_claims\""
                + ", claims=\"" + ex.claimsBase64() + "\"";

        log.info("Claims challenge issued: action={} authContext={} path={}",
                ex.getReason(), ex.getAuthContextId(), request.getRequestURI());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, challenge)
                .cacheControl(CacheControl.noStore())
                .body(new ClaimsChallengeResponse(
                        "insufficient_claims",
                        ex.claimsBase64(),
                        ex.getAuthorizationUri(),
                        ex.getReason(),
                        request.getRequestURI()));
    }
}
