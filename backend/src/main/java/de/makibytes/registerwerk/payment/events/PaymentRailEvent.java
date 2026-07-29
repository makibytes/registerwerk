package de.makibytes.registerwerk.payment.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Audit event covering every payment-rail catalog change (create, update, enable,
 * disable). {@code action} names the transition. {@code dualControlApproverId} is set only
 * for updates that changed the onchain chain-address mapping — those require a second
 * approver (they redirect where every dApp settling through this rail moves funds).
 */
public record PaymentRailEvent(String action, UUID railId, UUID actorId, String actorRole,
                               Map<String, Object> details, UUID dualControlApproverId) implements AuditableEvent {
    public PaymentRailEvent(String action, UUID railId, UUID actorId, String actorRole, Map<String, Object> details) {
        this(action, railId, actorId, actorRole, details, null);
    }

    public String eventType()   { return "PAYMENT_RAIL_" + action; }
    public String subjectType() { return "PaymentRail"; }
    public UUID   subjectId()   { return railId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
    public UUID   dualControlApproverId() { return dualControlApproverId; }
}
