package de.makibytes.registerwerk.web.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

final class SecurityUtils {

    private static final Logger log = LoggerFactory.getLogger(SecurityUtils.class);

    private SecurityUtils() {}

    static boolean isAdminOrAudit(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_REGISTRY_ADMIN")
                        || a.getAuthority().equals("ROLE_AUDIT"));
    }

    static UUID extractEntityId(Authentication auth) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return null;
        String id = jwt.getClaimAsString("entity_id");
        if (id == null) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid entity_id claim in JWT: {}", id);
            return null;
        }
    }
}
