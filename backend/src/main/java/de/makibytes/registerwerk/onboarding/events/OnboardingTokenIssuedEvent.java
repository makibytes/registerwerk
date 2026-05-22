package de.makibytes.registerwerk.onboarding.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record OnboardingTokenIssuedEvent(UUID entityId, UUID actorId, String actorRole) implements AuditableEvent {
    public String eventType()   { return "ONBOARDING_TOKEN_ISSUED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() { return Map.of(); }
}
