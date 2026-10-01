package de.makibytes.registerwerk.customer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record ClientClassifiedEvent(UUID entityId, UUID actorId, String actorRole, String clientCategory,
                                    String previousCategory, String reason, UUID evidenceDocumentId)
        implements AuditableEvent {

    public ClientClassifiedEvent(UUID entityId, UUID actorId, String actorRole, String clientCategory) {
        this(entityId, actorId, actorRole, clientCategory, null, null, null);
    }

    public String eventType()   { return "CLIENT_CLASSIFIED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("clientCategory", clientCategory);
        if (previousCategory != null) p.put("previousCategory", previousCategory);
        if (reason != null && !reason.isBlank()) p.put("reason", reason);
        if (evidenceDocumentId != null) p.put("evidenceDocumentId", evidenceDocumentId.toString());
        return p;
    }
}
