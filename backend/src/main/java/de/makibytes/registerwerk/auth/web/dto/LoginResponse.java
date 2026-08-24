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
    long expiresAt
) {}
