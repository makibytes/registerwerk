package de.makibytes.registerwerk.kyc.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** The PEP status of a natural person changed (written from a confirmed screening hit, 6-17). */
public record NaturalPersonPepStatusChangedEvent(UUID naturalPersonId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "NATURAL_PERSON_PEP_STATUS_CHANGED"; }
    public String subjectType() { return "NaturalPerson"; }
    public UUID   subjectId()   { return naturalPersonId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
