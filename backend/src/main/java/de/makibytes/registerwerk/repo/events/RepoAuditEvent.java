package de.makibytes.registerwerk.repo.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * One audit record for every state change of the repo desk (RFQ, quote, participation, trade
 * lifecycle, operator dispute resolution). {@code action} is e.g. {@code QUOTE_ACCEPTED} and becomes
 * the audit event type {@code REPO_QUOTE_ACCEPTED}.
 */
public record RepoAuditEvent(String subjectType, UUID subjectId, String action, UUID actorId, String actorRole,
                             Map<String, Object> details, UUID dualControlApproverId) implements AuditableEvent {

    public static RepoAuditEvent of(String subjectType, UUID subjectId, String action, UUID actorId, Map<String, Object> details) {
        return new RepoAuditEvent(subjectType, subjectId, action, actorId, "TRADER", details, null);
    }

    @Override public String eventType() { return "REPO_" + action; }
    @Override public Map<String, Object> payload() { return details == null ? Map.of() : details; }
}
