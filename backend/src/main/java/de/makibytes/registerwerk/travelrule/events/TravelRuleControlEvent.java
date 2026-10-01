package de.makibytes.registerwerk.travelrule.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Audit record for Travel Rule control actions that are not an outbound message dispatch: wallet-control
 * proofs, inbound peer management, rejected/conflicting inbound deliveries, delivery alerts (stale
 * PENDING_SEND, exhausted retries) and CASP-register decisions. {@code eventType} is one of the
 * {@code TRAVEL_RULE_*} / {@code CASP_*} constants below.
 */
public record TravelRuleControlEvent(String eventType, String subjectType, UUID subjectId, UUID actorId,
                                     String actorRole, UUID dualControlApproverId,
                                     Map<String, Object> details) implements AuditableEvent {

    public static final String WALLET_PROOF_VERIFIED = "TRAVEL_RULE_WALLET_PROOF_VERIFIED";
    public static final String WALLET_PROOF_REVOKED = "TRAVEL_RULE_WALLET_PROOF_REVOKED";
    public static final String PEER_CREATED = "TRAVEL_RULE_PEER_CREATED";
    public static final String PEER_DISABLED = "TRAVEL_RULE_PEER_DISABLED";
    public static final String INBOUND_REJECTED = "TRAVEL_RULE_INBOUND_REJECTED";
    public static final String INBOUND_CONFLICT = "TRAVEL_RULE_INBOUND_CONFLICT";
    public static final String INBOUND_INCOMPLETE = "TRAVEL_RULE_INBOUND_INCOMPLETE";
    public static final String DELIVERY_ALERT = "TRAVEL_RULE_DELIVERY_ALERT";
    public static final String CASP_DID_MISMATCH = "CASP_REGISTER_DID_MISMATCH";

    public TravelRuleControlEvent(String eventType, String subjectType, UUID subjectId, UUID actorId,
                                  String actorRole, Map<String, Object> details) {
        this(eventType, subjectType, subjectId, actorId, actorRole, null, details);
    }

    @Override public String subjectType() { return subjectType; }
    @Override public UUID subjectId() { return subjectId; }
    @Override public Map<String, Object> payload() { return details != null ? details : Map.of(); }
    @Override public UUID dualControlApproverId() { return dualControlApproverId; }
}
