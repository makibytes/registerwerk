package de.makibytes.registerwerk.stepup.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Generic 4-eyes evidence (P4C-4): published by {@code StepUpEnforcementAspect} for EVERY
 * {@code @RequiresStepUp(requireSecondApprover = true)} request, after the step-up and dual-control
 * checks and before the guarded method runs. It puts "who initiated, who approved, which
 * action" into the tamper-evident audit log without any per-controller wiring, so wallet, claim,
 * RPC-node and token-admin endpoints are covered alike. Domain events additionally carry the
 * approver via {@link #dualControlApproverId()} where the domain record needs it.
 *
 * <p>{@code requestId} correlates this entry with the domain audit event and the
 * {@code blockchain_transaction.approver_id} row of the same request.
 */
public record DualControlApprovedEvent(
        UUID initiatorId,
        String initiatorRole,
        UUID approverId,
        UUID requestId,
        String reason,
        String httpMethod,
        String requestPath,
        String stepUpMode)
        implements AuditableEvent {

    @Override public String eventType()   { return "DUAL_CONTROL_APPROVED"; }
    @Override public String subjectType() { return "StepUpAction"; }
    @Override public UUID   subjectId()   { return requestId; }
    @Override public UUID   actorId()     { return initiatorId; }
    @Override public String actorRole()   { return initiatorRole; }
    @Override public UUID   correlationId() { return requestId; }
    @Override public UUID   dualControlApproverId() { return approverId; }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reason", reason);
        p.put("httpMethod", httpMethod);
        p.put("requestPath", requestPath);
        p.put("stepUpMode", stepUpMode);
        return p;
    }
}
