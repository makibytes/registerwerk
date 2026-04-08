package de.makibytes.registerwerk.application.audit;

import de.makibytes.registerwerk.domain.audit.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Publishes audit events as Spring ApplicationEvents.
 * Consumers (e.g. {@link AuditEventPersistenceListener}) handle persistence asynchronously.
 */
@Component
public class AuditEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditEventPublisher.class);

    private final ApplicationEventPublisher eventPublisher;

    public AuditEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Creates an {@link AuditEvent} and publishes it as a Spring event.
     *
     * @param eventType   domain event type, e.g. "ENTITY_CREATED"
     * @param subjectType aggregate type, e.g. "LegalEntity"
     * @param subjectId   ID of the affected aggregate
     * @param actorId     ID of the user who performed the action (nullable)
     * @param actorRole   role of the actor (nullable)
     * @param payload     arbitrary structured data about the event (nullable)
     */
    public void publish(
            String eventType,
            String subjectType,
            UUID subjectId,
            UUID actorId,
            String actorRole,
            Map<String, Object> payload) {

        AuditEvent event = new AuditEvent();
        event.setEventType(eventType);
        event.setSubjectType(subjectType);
        event.setSubjectId(subjectId);
        event.setActorId(actorId);
        event.setActorRole(actorRole);
        event.setPayload(payload);

        log.debug("Publishing audit event: type={}, subject={}/{}", eventType, subjectType, subjectId);
        eventPublisher.publishEvent(new AuditEventSpringEvent(this, event));
    }
}
