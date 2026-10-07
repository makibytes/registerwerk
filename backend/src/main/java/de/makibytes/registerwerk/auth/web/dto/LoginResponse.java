package de.makibytes.registerwerk.auth.web.dto;

import java.util.List;

/**
 * No {@code token} field — the bearer token is set as an httpOnly {@code rw_session} cookie
 * (see {@code SessionCookieService}) rather than returned in the body, so no script running on
 * the page (including an XSS payload) can read it. Everything here is informational: claims a
 * legitimate script needs for UI state, not anything that grants access on its own.
 */
public record LoginResponse(
    String userId,
    List<String> roles,
    String email,
    String name,
    String entityId,
    /** Display name of {@code entityId} — only populated when {@code impersonating}. */
    String entityName,
    /** True when this session is a REGISTRY_ADMIN impersonating {@code entityId} (JWT {@code imp} claim). */
    boolean impersonating,
    long expiresAt,
    /** READ_ONLY or ACT_ON_BEHALF while {@code impersonating}, else null. The customer app shows a banner and disables writes in READ_ONLY. */
    String impersonationMode,
    /**
     * True when the account must change its password first ({@code must_change_password}): the session is
     * restricted to {@code POST /api/v1/auth/change-password}, every other endpoint answers 403
     * {@code PASSWORD_CHANGE_REQUIRED}.
     */
    boolean passwordChangeRequired
) {
    public LoginResponse(String userId, List<String> roles, String email, String name, String entityId,
                         String entityName, boolean impersonating, long expiresAt, String impersonationMode) {
        this(userId, roles, email, name, entityId, entityName, impersonating, expiresAt, impersonationMode, false);
    }
}
