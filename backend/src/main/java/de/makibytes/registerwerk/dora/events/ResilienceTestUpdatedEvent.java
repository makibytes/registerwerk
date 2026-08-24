package de.makibytes.registerwerk.dora.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** DORA Art. 24/25 resilience test result updated — e.g. closing out FINDINGS_OPEN. */
public record ResilienceTestUpdatedEvent(UUID testId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "DORA_RESILIENCE_TEST_UPDATED"; }
    public String subjectType() { return "ResilienceTest"; }
    public UUID   subjectId()   { return testId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
