package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.events.EntityCreatedEvent;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Screens a legal entity as soon as it is registered (GwG §10 ongoing monitoring starts at
 * onboarding, not whenever an operator happens to click "screen" manually). Previously a newly
 * created entity had zero {@link ScreeningRun} rows until either an operator manually triggered
 * one or the daily {@link ScreeningService#periodicRefresh()} job ran — which itself only
 * re-screens entities that already have a prior run, so a never-screened entity would stay
 * never-screened indefinitely. {@link de.makibytes.registerwerk.screening.api.ScreeningGate}'s
 * fail-closed semantics meant this was silently blocking (not bypassing) KYC approval rather
 * than exposing a real gap — but it left every new entity in a permanently "blocked" state
 * until manual intervention, which does not scale and is easy to overlook.
 */
@Component
class EntityCreatedScreeningListener {

    private static final Logger log = LoggerFactory.getLogger(EntityCreatedScreeningListener.class);

    private final ScreeningService screeningService;
    private final LegalEntityRepository legalEntityRepository;

    EntityCreatedScreeningListener(ScreeningService screeningService, LegalEntityRepository legalEntityRepository) {
        this.screeningService = screeningService;
        this.legalEntityRepository = legalEntityRepository;
    }

    @ApplicationModuleListener
    void onEntityCreated(EntityCreatedEvent event) {
        LegalEntity entity = legalEntityRepository.findById(event.entityId()).orElse(null);
        if (entity == null) {
            log.warn("Entity-created screening trigger: entity {} no longer exists, skipping.", event.entityId());
            return;
        }
        try {
            screeningService.screenEntity(entity.getId(), entity.getCurrentName(),
                    entity.getRegistrationCountry(), entity.getLeiCode(), ScreeningTrigger.ENTITY_ONBOARDING);
        } catch (Exception e) {
            // A screening-provider outage must not prevent the entity record itself from
            // existing — the fail-closed ScreeningGate already blocks KYC approval for an
            // entity with no completed run, so nothing is bypassed by a failed attempt here.
            log.error("Initial screening failed for newly created entity={}", event.entityId(), e);
        }
    }
}
