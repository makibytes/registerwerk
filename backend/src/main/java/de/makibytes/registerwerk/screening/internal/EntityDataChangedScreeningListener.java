package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.customer.events.EntityRiskDataChangedEvent;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Re-screens an entity after a risk-relevant master-data change, merger or reactivation (6-24). */
@Component
class EntityDataChangedScreeningListener {

    private static final Logger log = LoggerFactory.getLogger(EntityDataChangedScreeningListener.class);

    private final ScreeningService screeningService;

    EntityDataChangedScreeningListener(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    @ApplicationModuleListener
    void on(EntityRiskDataChangedEvent event) {
        try {
            screeningService.screenRegisteredEntity(event.entityId(), ScreeningTrigger.ENTITY_DATA_CHANGED);
        } catch (RuntimeException e) {
            // Fail closed is the gate's job: no completed clean run keeps KYC approval/reactivation blocked.
            log.error("Re-screening after data change failed for entity={}", event.entityId(), e);
        }
    }
}
