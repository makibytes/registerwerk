package de.makibytes.registerwerk.kyc.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record BeneficialOwnerCeasedEvent(UUID beneficialOwnerId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "BENEFICIAL_OWNER_CEASED"; }
    public String subjectType() { return "BeneficialOwner"; }
    public UUID   subjectId()   { return beneficialOwnerId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
