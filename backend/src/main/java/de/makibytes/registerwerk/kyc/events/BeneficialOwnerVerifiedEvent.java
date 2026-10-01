package de.makibytes.registerwerk.kyc.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** A beneficial owner's identity was verified against a stored evidence document (6-17). */
public record BeneficialOwnerVerifiedEvent(UUID beneficialOwnerId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "BENEFICIAL_OWNER_VERIFIED"; }
    public String subjectType() { return "BeneficialOwner"; }
    public UUID   subjectId()   { return beneficialOwnerId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
