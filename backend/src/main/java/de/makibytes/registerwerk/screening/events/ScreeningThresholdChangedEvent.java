package de.makibytes.registerwerk.screening.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** The configured match threshold differs from the one used by the latest recorded run (6-19). */
public record ScreeningThresholdChangedEvent(BigDecimal previous, BigDecimal current)
        implements AuditableEvent {

    public String eventType()   { return "SCREENING_THRESHOLD_CHANGED"; }
    public String subjectType() { return "ScreeningConfig"; }
    /** audit_event.subject_id is NOT NULL; a stable id stands for the singleton subject. */
    public static final UUID SUBJECT = UUID.nameUUIDFromBytes("screening-config".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    public UUID   subjectId()   { return SUBJECT; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() {
        return Map.of("previousThreshold", String.valueOf(previous), "currentThreshold", String.valueOf(current));
    }
}
