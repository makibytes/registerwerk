package de.makibytes.registerwerk.stepup.web.dto;

/**
 * The claims challenge, repeated in the response body.
 *
 * <p>The authoritative carrier is the {@code WWW-Authenticate} header, but a header only reaches
 * browser JavaScript if every hop cooperates — Kong's CORS {@code exposed_headers}, nginx, and
 * any future proxy. The body always survives, so the SPA reads the header when present and falls
 * back to this. Cheap insurance against a failure mode that would otherwise look like an
 * inexplicable logout loop.
 *
 * @param error            always {@code "insufficient_claims"}
 * @param claims           base64 claims request to pass to {@code /authorize}
 * @param authorizationUri authorization endpoint to send the user to
 * @param reason           the protected action, for logging and UI context
 * @param path             request path, matching {@code ErrorResponse}'s shape
 */
public record ClaimsChallengeResponse(
        String error,
        String claims,
        String authorizationUri,
        String reason,
        String path) {
}
