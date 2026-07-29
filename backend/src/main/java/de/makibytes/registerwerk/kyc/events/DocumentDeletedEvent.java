package de.makibytes.registerwerk.kyc.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record DocumentDeletedEvent(UUID documentId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "KYC_DOCUMENT_DELETED"; }
    public String subjectType() { return "KycDocument"; }
    public UUID   subjectId()   { return documentId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
