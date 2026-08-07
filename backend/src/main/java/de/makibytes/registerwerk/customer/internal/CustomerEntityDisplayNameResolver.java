package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.auth.api.EntityDisplayNameResolver;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Implements {@code auth}'s {@link EntityDisplayNameResolver} port — see its Javadoc for why
 * this is wired by interface rather than {@code auth.web} depending on
 * {@link LegalEntityRepository} directly.
 */
@Component
class CustomerEntityDisplayNameResolver implements EntityDisplayNameResolver {

    private final LegalEntityRepository legalEntityRepository;

    CustomerEntityDisplayNameResolver(LegalEntityRepository legalEntityRepository) {
        this.legalEntityRepository = legalEntityRepository;
    }

    @Override
    public String resolveName(UUID entityId) {
        if (entityId == null) {
            return null;
        }
        return legalEntityRepository.findById(entityId)
                .map(e -> e.getCurrentName())
                .orElse(null);
    }
}
