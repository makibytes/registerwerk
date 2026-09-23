package de.makibytes.registerwerk.screening.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Fired when a screening run returns at least one sanctions/PEP hit, on any trigger — including
 * the daily re-screen of already-approved subjects (GwG §10 Abs. 1 Nr. 5). The hits are still
 * <em>unreviewed</em> at this point.
 *
 * <p>Deliberately has no consumer that acts on chain yet: whether an unreviewed hit should
 * suspend the org and revoke its claims, only freeze tokens, or open a review with an SLA is a
 * parked product decision (PARK-T2-19). Until then the event is audited only; the decision is a
 * listener switch on this event.
 *
 * @param subjectType {@code LEGAL_ENTITY} or {@code NATURAL_PERSON}
 * @param subjectId   legal entity id or natural person id
 */
public record ScreeningHitDetectedEvent(
        UUID runId, String subjectType, UUID subjectId, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "SCREENING_HIT_DETECTED"; }
    public String subjectType() { return subjectType; }
    public UUID   subjectId()   { return subjectId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
