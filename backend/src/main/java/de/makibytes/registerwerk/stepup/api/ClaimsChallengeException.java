package de.makibytes.registerwerk.stepup.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Signals that the caller's access token lacks the Conditional Access authentication context
 * this action requires, and that they should re-authenticate to obtain one.
 *
 * <p><strong>Deliberately not an {@code AccessDeniedException}.</strong> A claims challenge is a
 * protocol step in the middle of a legitimate request, not a refusal. Extending
 * {@code AccessDeniedException} would let {@code GlobalExceptionHandler} collapse it into a flat
 * 403 — losing the challenge the client needs — and would trip the audit layer's rejection
 * recording, filling {@code audit_event} with rows for something that is not a denied action.
 *
 * <p>Per RFC 6750 and Microsoft's claims-challenge specification the response is <strong>401</strong>,
 * not 403.
 */
public class ClaimsChallengeException extends RuntimeException {

    private final String authContextId;
    private final String authorizationUri;
    private final String reason;

    public ClaimsChallengeException(String authContextId, String authorizationUri, String reason) {
        super("This action requires Conditional Access authentication context '" + authContextId
                + "'. Re-authenticate with the supplied claims challenge.");
        this.authContextId = authContextId;
        this.authorizationUri = authorizationUri;
        this.reason = reason;
    }

    /**
     * The base64 claims request the client appends to {@code /authorize}. Microsoft's format is
     * base64 of the minified JSON, unencoded — the client base64-decodes it and URL-encodes the
     * result as the {@code claims} query parameter.
     */
    public String claimsBase64() {
        String json = "{\"access_token\":{\"acrs\":{\"essential\":true,\"value\":\"" + authContextId + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public String getAuthContextId() {
        return authContextId;
    }

    public String getAuthorizationUri() {
        return authorizationUri;
    }

    public String getReason() {
        return reason;
    }
}
