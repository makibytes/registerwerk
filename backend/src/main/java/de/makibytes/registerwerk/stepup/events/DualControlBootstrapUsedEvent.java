package de.makibytes.registerwerk.stepup.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A dual-control gate was satisfied by the bootstrap exception (fewer than two enabled, TOTP-enrolled
 * REGISTRY_ADMINs, so no second approver can exist). Recorded so the single-actor path is visible in
 * the audit log and never silent.
 */
public record DualControlBootstrapUsedEvent(UUID initiatorId, String initiatorRole, UUID requestId, String reason,
                                            String httpMethod, String requestPath, long enrolledAdmins)
        implements AuditableEvent {

    @Override public String eventType()   { return "DUAL_CONTROL_BOOTSTRAP_USED"; }
    @Override public String subjectType() { return "StepUpAction"; }
    @Override public UUID   subjectId()   { return requestId; }
    @Override public UUID   actorId()     { return initiatorId; }
    @Override public String actorRole()   { return initiatorRole; }
    @Override public UUID   correlationId() { return requestId; }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reason", reason);
        p.put("httpMethod", httpMethod);
        p.put("requestPath", requestPath);
        p.put("bootstrap", true);
        p.put("enrolledRegistryAdmins", enrolledAdmins);
        return p;
    }
}
