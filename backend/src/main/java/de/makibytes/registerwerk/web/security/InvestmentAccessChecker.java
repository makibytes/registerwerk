package de.makibytes.registerwerk.web.security;

import de.makibytes.registerwerk.domain.asset.AssetHolder;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetHolderRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Security helper that determines whether the authenticated user may read a given
 * investment (asset holder) record.
 *
 * <p>REGISTRY_ADMIN and AUDIT roles always have access. Investors may only read
 * holding records whose {@code investorId} matches their {@code entity_id} JWT claim.
 */
@Component
public class InvestmentAccessChecker {

    private final AssetHolderRepository holderRepository;

    public InvestmentAccessChecker(AssetHolderRepository holderRepository) {
        this.holderRepository = holderRepository;
    }

    public boolean canRead(UUID holderId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        if (SecurityUtils.isAdminOrAudit(auth)) return true;

        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) return false;

        return holderRepository.findById(holderId)
                .map(h -> entityId.equals(h.getInvestorId()))
                .orElse(false);
    }
}
