package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
class AuditEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditEventRecorder.class);

    private final AuditEventRepository repository;

    AuditEventRecorder(AuditEventRepository repository) {
        this.repository = repository;
    }

    @ApplicationModuleListener
    void on(AuditableEvent event) {
        AuditEvent ae = AuditEvent.from(event);
        enrichActorName(ae, event);
        repository.save(ae);
        log.debug("Recorded audit event: type={}, subject={}/{}", event.eventType(), event.subjectType(), event.subjectId());
        // Intentionally no try/catch: a failed audit write must propagate and roll back
        // the originating transaction. Silent swallowing would violate eWpRV §6 integrity.
    }

    private static void enrichActorName(AuditEvent ae, AuditableEvent source) {
        Map<String, Object> payload = ae.getPayload();
        if (payload != null && payload.containsKey("actorName")) return;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return;
        Map<String, Object> enriched = payload != null ? new HashMap<>(payload) : new HashMap<>();
        enriched.put("actorName", auth.getName());
        ae.setPayload(enriched);
    }
}
