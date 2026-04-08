package de.makibytes.registerwerk.application.audit;

import de.makibytes.registerwerk.infrastructure.persistence.jpa.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Persists {@link AuditEventSpringEvent}s asynchronously to the database.
 */
@Component
public class AuditEventPersistenceListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventPersistenceListener.class);

    private final AuditEventRepository auditEventRepository;

    public AuditEventPersistenceListener(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @Async
    @EventListener
    public void onAuditEvent(AuditEventSpringEvent springEvent) {
        try {
            auditEventRepository.save(springEvent.getAuditEvent());
            log.debug("Persisted audit event: type={}, subject={}",
                springEvent.getAuditEvent().getEventType(),
                springEvent.getAuditEvent().getSubjectId());
        } catch (Exception e) {
            log.error("Failed to persist audit event: {}", springEvent.getAuditEvent().getEventType(), e);
        }
    }
}
