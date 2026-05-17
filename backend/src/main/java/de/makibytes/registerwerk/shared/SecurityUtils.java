package de.makibytes.registerwerk.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class SecurityUtils {

    private static final Logger log = LoggerFactory.getLogger(SecurityUtils.class);

    private SecurityUtils() {}

    public static boolean isAdminOrAudit(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_REGISTRY_ADMIN")
                        || a.getAuthority().equals("ROLE_AUDIT"));
    }

    /** Returns true when the token was minted by the impersonation flow ({@code imp: true}). */
    public static boolean isImpersonatingAdmin(Authentication auth) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return false;
        return Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp"));
    }

    public static UUID extractEntityId(Authentication auth) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return null;
        String id = claim(jwt, "entity_id", "entityId");
        if (id == null) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid entity_id claim in JWT: {}", id);
            return null;
        }
    }

    public static UUID extractUserId(Authentication auth) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return tryParseUuid(auth != null ? auth.getName() : null);
        return tryParseUuid(claim(jwt, "sub"));
    }

    public static String extractEmail(Authentication auth) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return null;
        return claim(jwt, "email", "preferred_username", "upn");
    }

    public static String extractDisplayName(Authentication auth) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return null;
        return claim(jwt, "name", "preferred_username", "email");
    }

    public static List<String> extractRoles(Authentication auth) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return Collections.emptyList();
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? Collections.emptyList() : roles;
    }

    private static UUID tryParseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String claim(Jwt jwt, String... names) {
        for (String name : names) {
            String value = jwt.getClaimAsString(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
