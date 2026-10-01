package de.makibytes.registerwerk.screening.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Raised when a screening batch (nightly refresh or retry round) could not complete for some
 * subjects. While the grace window lasts the gate keeps relying on the last good result; after it the
 * subjects block again (6-18). Also emitted when retries for a subject are exhausted.
 */
public record ScreeningProviderDegradedEvent(String reason, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "SCREENING_PROVIDER_DEGRADED"; }
    public String subjectType() { return "ScreeningProvider"; }
    /** audit_event.subject_id is NOT NULL; a stable id stands for the singleton subject. */
    public static final UUID SUBJECT = UUID.nameUUIDFromBytes("screening-provider".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    public UUID   subjectId()   { return SUBJECT; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() {
        java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("reason", reason);
        if (details != null) {
            p.putAll(details);
        }
        return p;
    }
}
