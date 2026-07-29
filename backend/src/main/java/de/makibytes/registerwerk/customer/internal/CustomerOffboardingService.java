package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.events.CustomerOffboardedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Orchestrates the customer off-ramp: previously {@code LegalEntityService.dissolveEntity} (the
 * only "exit" path) flipped one enum field and cascaded to nothing — a dissolved entity's users
 * stayed enabled, its open trade listings stayed live, its ASSET_TOKEN_ADMIN grants stayed
 * active, and nothing signalled that its issued assets or investor holdings needed any
 * follow-up. This service is the real off-ramp: it owns the parts that belong to the
 * {@code customer} module directly (disabling users, the terminal status transition) and
 * publishes {@link CustomerOffboardedEvent} for every other module's own cleanup — asset grants,
 * trade listings, and portfolio-migration requests — following the same event-driven pattern as
 * {@code AssetRedeemedEvent}/{@code AssetRedemptionListener} rather than reaching across module
 * boundaries directly (customer has no dependency on asset/trading/registertransfer, and must
 * not gain one — those modules already depend on customer, so the reverse edge would cycle).
 */
@Service
@Transactional
public class CustomerOffboardingService {

    private static final Logger log = LoggerFactory.getLogger(CustomerOffboardingService.class);

    private final LegalEntityRepository entityRepository;
    private final AppUserRepository userRepository;
    private final ApplicationEventPublisher events;

    public CustomerOffboardingService(LegalEntityRepository entityRepository,
                                       AppUserRepository userRepository,
                                       ApplicationEventPublisher events) {
        this.entityRepository = entityRepository;
        this.userRepository = userRepository;
        this.events = events;
    }

    public LegalEntity terminate(UUID entityId, UUID actorId, String actorRole, String reason) {
        LegalEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
        if (entity.getStatus() == EntityStatus.CLOSED || entity.getStatus() == EntityStatus.DISSOLVED) {
            throw new IllegalStateException(
                    "Entity " + entityId + " is already " + entity.getStatus() + " — cannot terminate again");
        }

        int disabledCount = 0;
        for (AppUser user : userRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId)) {
            if (user.isEnabled()) {
                user.setEnabled(false);
                userRepository.save(user);
                disabledCount++;
            }
        }

        entity.setStatus(EntityStatus.CLOSED);
        LegalEntity saved = entityRepository.save(entity);

        events.publishEvent(new CustomerOffboardedEvent(entityId, actorId, actorRole, reason));
        log.warn("Customer offboarded: entityId={} usersDisabled={} reason={}", entityId, disabledCount, reason);
        return saved;
    }
}
