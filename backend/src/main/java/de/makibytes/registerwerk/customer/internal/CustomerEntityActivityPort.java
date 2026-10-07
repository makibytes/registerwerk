package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.auth.api.EntityActivityPort;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implements {@code auth}'s {@link EntityActivityPort} for the session guard. */
@Component
class CustomerEntityActivityPort implements EntityActivityPort {

    private final LegalEntityRepository legalEntityRepository;

    CustomerEntityActivityPort(LegalEntityRepository legalEntityRepository) {
        this.legalEntityRepository = legalEntityRepository;
    }

    @Override
    public boolean isTerminated(UUID entityId) {
        if (entityId == null) {
            return false;
        }
        return legalEntityRepository.findById(entityId)
                .map(e -> e.getStatus() == EntityStatus.CLOSED || e.getStatus() == EntityStatus.DISSOLVED
                        || e.getStatus() == EntityStatus.PENDING_REACTIVATION)
                .orElse(false);
    }

    @Override
    public java.util.Optional<UUID> idpTenantOf(UUID entityId) {
        if (entityId == null) {
            return java.util.Optional.empty();
        }
        return legalEntityRepository.findById(entityId).map(e -> e.getIdpTenantId());
    }
}
