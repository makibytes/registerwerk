package de.makibytes.registerwerk.screening.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Fired when a compliance officer accepts (clears) a sanctions/PEP screening hit as a
 * false positive (GwG §8 documentation duty). Previously this decision — including the
 * mandatory reason and, for high-score hits, the dual-control approver — was recorded
 * only on the mutable {@code ScreeningHit} entity, not in the tamper-evident audit log.
 */
public record SanctionsHitAcceptedEvent(
        UUID hitId, UUID actorId, String actorRole, UUID dualControlApproverId, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "SANCTIONS_HIT_ACCEPTED"; }
    public String subjectType() { return "ScreeningHit"; }
    public UUID   subjectId()   { return hitId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
    public UUID   dualControlApproverId() { return dualControlApproverId; }
}
