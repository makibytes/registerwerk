package de.makibytes.registerwerk.screening.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * A PEP hit on a natural person was confirmed (not dismissed as a false positive) under step-up and
 * a second approver. The hit stays unresolved for the screening gate until an enhanced-due-diligence
 * approval is recorded. The kyc module listens to this to set the person's PEP status (6-17).
 */
public record ScreeningPepConfirmedEvent(
        UUID hitId, UUID naturalPersonId, UUID actorId, String actorRole, UUID dualControlApproverId,
        Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "SCREENING_PEP_CONFIRMED"; }
    public String subjectType() { return "ScreeningHit"; }
    public UUID   subjectId()   { return hitId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
