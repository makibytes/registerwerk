package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Fired when an operator annotates a FAILED/TIMEOUT {@code blockchain_transaction} as handled —
 * the global transaction console's only write action, since a safe automated gas-bump resubmit
 * isn't implemented (no nonce/calldata is captured at submission time; see the console's
 * Javadoc). The note usually records what was done out-of-band via the chain's own tooling.
 */
public record BlockchainTxReviewedEvent(
        UUID transactionId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "BLOCKCHAIN_TX_REVIEWED"; }
    public String subjectType() { return "BlockchainTransaction"; }
    public UUID   subjectId()   { return transactionId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
