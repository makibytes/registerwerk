package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * A {@code blockchain_transaction} that had already been marked TIMEOUT was mined after all (P4B-5).
 * TIMEOUT means "not yet mined", so the poller keeps reading such rows and, on a final receipt,
 * completes them through the normal path; this event is the audit trail of that late outcome.
 * {@code outcome} is the resulting status (SUCCESS or FAILED). System-originated.
 */
public record BlockchainTxLateMinedEvent(UUID transactionId, String outcome, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "BLOCKCHAIN_TX_LATE_MINED_" + outcome; }
    public String subjectType() { return "BlockchainTransaction"; }
    public UUID   subjectId()   { return transactionId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
