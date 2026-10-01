package de.makibytes.registerwerk.screening.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * A re-screen returned a match that was already reviewed and accepted as a false positive (same
 * subject, same provider record fingerprint, inside the validity window, no score jump), so the new
 * hit inherited that decision instead of re-blocking the subject (6-18). One audit entry per carried
 * hit, naming the original hit and who decided it; nothing is ever carried silently.
 */
public record ScreeningHitCarriedForwardEvent(
        UUID hitId, String subjectType, UUID subjectId, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "SCREENING_HIT_CARRIED_FORWARD"; }
    public String subjectType() { return subjectType; }
    public UUID   subjectId()   { return subjectId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
