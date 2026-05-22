package de.makibytes.registerwerk.onboarding.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record OnboardingCompletedEvent(
        UUID entityId, UUID adminUserId, UUID actorId,
        String adminEmail, String entityName, String frontendUrl) implements AuditableEvent {
    public String actorRole()   { return null; }
    public String eventType()   { return "ONBOARDING_COMPLETED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() { return Map.of("adminEmail", adminEmail); }
}
