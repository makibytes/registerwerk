package de.makibytes.registerwerk.web.security;

import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Security helper that determines read access to an asset.
 * Admins and auditors have unrestricted read access; issuers can only read their own assets.
 */
@Component
public class AssetAccessChecker {

    private static final Logger log = LoggerFactory.getLogger(AssetAccessChecker.class);

    private final AssetRepository assetRepository;

    public AssetAccessChecker(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    /**
     * Returns true if the authenticated user may read the given asset.
     *
     * <ul>
     *   <li>REGISTRY_ADMIN and AUDIT roles have unrestricted access.</li>
     *   <li>Other authenticated users may read an asset only if their {@code entity_id}
     *       JWT claim matches the asset's {@code issuerId}.</li>
     * </ul>
     *
     * @param assetId UUID of the asset to check
     * @param auth    current Spring Security authentication
     * @return true if access is permitted
     */
    public boolean canRead(UUID assetId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }

        // Admins and auditors always have access
        boolean isAdminOrAudit = auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_REGISTRY_ADMIN")
                || a.getAuthority().equals("ROLE_AUDIT"));
        if (isAdminOrAudit) {
            return true;
        }

        // Check issuer ownership via entity_id claim
        Object principal = auth.getPrincipal();
        if (!(principal instanceof Jwt jwt)) {
            return false;
        }
        String claimedEntityId = jwt.getClaimAsString("entity_id");
        if (claimedEntityId == null) {
            return false;
        }
        try {
            UUID claimedUuid = UUID.fromString(claimedEntityId);
            Optional<Asset> asset = assetRepository.findById(assetId);
            return asset.map(a -> claimedUuid.equals(a.getIssuerId())).orElse(false);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid entity_id claim in JWT: {}", claimedEntityId);
            return false;
        }
    }
}
