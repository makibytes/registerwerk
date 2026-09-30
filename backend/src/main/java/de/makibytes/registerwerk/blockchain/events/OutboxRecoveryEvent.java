package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * An action on a stuck durable-outbox payload (P4B-4): {@code STUCK_DETECTED}, {@code REPRICED},
 * {@code CANCELLED} (a cancel replacement was signed), {@code ABANDONED}, {@code REBROADCAST},
 * {@code LEASE_REPAIRED}. Automatic actions carry no actor ({@code SYSTEM}); operator actions carry
 * the initiator and the validated second approver.
 */
public record OutboxRecoveryEvent(UUID submissionId, String action, UUID actorId, String actorRole,
        UUID approverId, UUID correlation, Map<String, Object> details) implements AuditableEvent {

    public String eventType()   { return "EVM_OUTBOX_" + action; }
    public String subjectType() { return "EvmSignedSubmission"; }
    public UUID   subjectId()   { return submissionId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
    @Override public UUID correlationId() { return correlation; }
    @Override public UUID dualControlApproverId() { return approverId; }
}
