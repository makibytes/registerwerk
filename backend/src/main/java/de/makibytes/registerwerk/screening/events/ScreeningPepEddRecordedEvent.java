package de.makibytes.registerwerk.screening.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** An approved EDD record now covers the confirmed-PEP hits of a person until the review date. */
public record ScreeningPepEddRecordedEvent(
        UUID naturalPersonId, UUID actorId, String actorRole, UUID dualControlApproverId,
        Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "SCREENING_PEP_EDD_RECORDED"; }
    public String subjectType() { return "NaturalPerson"; }
    public UUID   subjectId()   { return naturalPersonId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
