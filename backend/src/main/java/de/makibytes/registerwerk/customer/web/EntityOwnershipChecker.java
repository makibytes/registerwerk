package de.makibytes.registerwerk.customer.web;

import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Helper that determines whether the authenticated user owns a given entity.
 * The entity ID is read from the {@code entity_id} JWT claim.
 */
@Component
public class EntityOwnershipChecker {

    private static final Logger log = LoggerFactory.getLogger(EntityOwnershipChecker.class);

    private final LegalEntityRepository legalEntityRepository;

    public EntityOwnershipChecker(LegalEntityRepository legalEntityRepository) {
        this.legalEntityRepository = legalEntityRepository;
    }

    /**
     * Returns true if the JWT's {@code entity_id} claim matches the given entityId.
     *
     * @param entityId  the entity UUID to check ownership for
     * @param auth      current Spring Security authentication
     * @return true if the authenticated principal owns the entity
     */
    public boolean isOwner(UUID entityId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        UUID claimedEntityId = SecurityUtils.extractEntityId(auth);
        return claimedEntityId != null && claimedEntityId.equals(entityId);
    }

    /**
     * True if the authenticated caller is a RELATIONSHIP_MANAGER assigned to this entity
     * (F-BLOCKER-15) — read-only client-servicing access, distinct from {@link #isOwner}
     * (the entity's own users) and from REGISTRY_ADMIN/AUDIT (unrestricted staff access).
     */
    public boolean isAssignedRelationshipManager(UUID entityId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        UUID userId = SecurityUtils.extractUserId(auth);
        if (userId == null) {
            return false;
        }
        return legalEntityRepository.findById(entityId)
                .map(e -> userId.equals(e.getAssignedRelationshipManagerId()))
                .orElse(false);
    }
}
