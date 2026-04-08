package de.makibytes.registerwerk.application.audit;

import de.makibytes.registerwerk.domain.audit.AuditEvent;
import org.springframework.context.ApplicationEvent;

/**
 * Spring ApplicationEvent wrapper around {@link AuditEvent}.
 * Used to decouple publication from persistence.
 */
public class AuditEventSpringEvent extends ApplicationEvent {

    private final AuditEvent auditEvent;

    public AuditEventSpringEvent(Object source, AuditEvent auditEvent) {
        super(source);
        this.auditEvent = auditEvent;
    }

    public AuditEvent getAuditEvent() {
        return auditEvent;
    }
}
