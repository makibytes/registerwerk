package de.makibytes.registerwerk.customer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record CompanyUserPasswordResetRequestedEvent(
        UUID entityId, UUID userId, UUID actorId, String actorRole,
        String email, String resetLink) implements AuditableEvent {
    public String eventType()   { return "COMPANY_USER_PASSWORD_RESET_REQUESTED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() { return Map.of("userId", userId.toString()); }
}
