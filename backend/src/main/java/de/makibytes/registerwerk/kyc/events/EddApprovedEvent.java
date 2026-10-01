package de.makibytes.registerwerk.kyc.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** Enhanced due diligence of a confirmed PEP was approved under four-eyes, until a review date (6-17). */
public record EddApprovedEvent(UUID naturalPersonId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "KYC_EDD_APPROVED"; }
    public String subjectType() { return "NaturalPerson"; }
    public UUID   subjectId()   { return naturalPersonId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
