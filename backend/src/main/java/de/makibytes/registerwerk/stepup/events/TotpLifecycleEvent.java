package de.makibytes.registerwerk.stepup.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * TOTP lifecycle evidence (K3, 6-09): {@code ENROLMENT_STARTED}, {@code ENROLLED}, {@code DISENROLLED}
 * (self-service, current code required) and {@code RESET} (operator, step-up + second approver).
 * Never carries the secret. {@code approverId} is only set for {@code RESET}.
 */
public record TotpLifecycleEvent(String action, UUID userId, UUID actorId, String actorRole, UUID approverId)
        implements AuditableEvent {

    @Override public String eventType()   { return "TOTP_" + action; }
    @Override public String subjectType() { return "AppUser"; }
    @Override public UUID   subjectId()   { return userId; }
    @Override public UUID   dualControlApproverId() { return approverId; }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", action);
        p.put("userId", userId.toString());
        return p;
    }
}
