package de.makibytes.registerwerk.stepup.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Evidence for the in-app approval queue (T8-02): {@code CREATED}, {@code APPROVED}, {@code REJECTED},
 * {@code CANCELLED}, {@code CLAIMED}, {@code EXPIRED}. Never carries the request body (it can hold personal
 * data); the digest identifies exactly what was decided.
 *
 * <p>{@code CLAIMED} is the moment a bound approver token was minted: its {@code approvalTokenId} (the jti) is
 * the value the later {@code DUAL_CONTROL_APPROVED} entry repeats as {@code approvalTokenId}.
 */
public record ApprovalRequestEvent(
        String outcome,
        UUID requestId,
        UUID actorId,
        String actorRole,
        UUID requesterId,
        UUID approverId,
        String reason,
        String httpMethod,
        String requestPath,
        String targetDigest,
        String note,
        String approvalTokenId)
        implements AuditableEvent {

    @Override public String eventType()   { return "APPROVAL_REQUEST_" + outcome; }
    @Override public String subjectType() { return "ApprovalRequest"; }
    @Override public UUID   subjectId()   { return requestId; }
    @Override public UUID   correlationId() { return requestId; }
    @Override public UUID   dualControlApproverId() { return approverId; }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reason", reason);
        p.put("requesterId", requesterId != null ? requesterId.toString() : null);
        p.put("approverId", approverId != null ? approverId.toString() : null);
        p.put("httpMethod", httpMethod);
        p.put("requestPath", requestPath);
        p.put("targetDigest", targetDigest);
        if (note != null && !note.isBlank()) {
            p.put("note", note);
        }
        if (approvalTokenId != null) {
            p.put("approvalTokenId", approvalTokenId);
        }
        return p;
    }
}
