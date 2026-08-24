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
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_REGISTRY_ADMIN")
                        || a.getAuthority().equals("ROLE_AUDIT"));
    }

    /** Operator staff (registry-side) vs. customer-side (issuer/investor/trader/etc.) — the split
     *  {@code indexer.web.TokenTransferMapper} uses to decide technical vs. plain-language finality
     *  vocabulary. Deliberately excludes {@code ISSUER}: an issuer is a customer role even though
     *  they manage their own asset, per CLAUDE.md's "two user groups" split. */
    public static boolean isTechnicalRole(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_REGISTRY_ADMIN")
                        || a.getAuthority().equals("ROLE_AUDIT")
                        || a.getAuthority().equals("ROLE_COMPLIANCE_OFFICER")
                        || a.getAuthority().equals("ROLE_RELATIONSHIP_MANAGER"));
    }

    /** Returns true when the token was minted by the impersonation flow ({@code imp: true}). */
    public static boolean isImpersonatingAdmin(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) return false;
        return Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp"));
    }

    public static UUID extractEntityId(Authentication auth) {
        if (auth == null) return null;
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
        if (auth == null) return null;
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return tryParseUuid(auth.getName());
        return tryParseUuid(claim(jwt, "sub"));
    }

    public static String extractEmail(Authentication auth) {
        if (auth == null) return null;
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return null;
        return claim(jwt, "email", "preferred_username", "upn");
    }

    public static String extractDisplayName(Authentication auth) {
        if (auth == null) return null;
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return null;
        return claim(jwt, "name", "preferred_username", "email");
    }

    public static List<String> extractRoles(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) return Collections.emptyList();
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? Collections.emptyList() : roles;
    }

    /**
     * The actor's first JWT role, or {@code fallback} when the token carries none (or
     * {@code auth} is null — e.g. an unauthenticated rejected request). Centralizes a
     * one-line helper that was independently copy-pasted into ~12 controllers.
     */
    public static String primaryRole(Authentication auth, String fallback) {
        List<String> roles = extractRoles(auth);
        return roles.isEmpty() ? fallback : roles.get(0);
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
