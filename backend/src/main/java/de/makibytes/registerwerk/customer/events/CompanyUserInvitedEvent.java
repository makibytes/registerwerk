package de.makibytes.registerwerk.customer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record CompanyUserInvitedEvent(
        UUID entityId, UUID userId, UUID actorId, String actorRole,
        String email, String displayName, String inviteLink) implements AuditableEvent {
    public String eventType()   { return "COMPANY_USER_INVITED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() { return Map.of("userId", userId.toString(), "email", email); }
}
