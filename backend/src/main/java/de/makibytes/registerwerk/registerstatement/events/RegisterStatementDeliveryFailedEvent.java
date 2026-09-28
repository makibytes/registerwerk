package de.makibytes.registerwerk.registerstatement.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * A §19 statement could not be delivered because the holder has no lawful delivery channel left
 * (all users erased or disabled - GDPR Art. 17 vs. the statutory notice duty, T3-14). Surfaces in
 * the audit trail as the operator alert; the statement stays FAILED and is not retried.
 */
public record RegisterStatementDeliveryFailedEvent(
        UUID statementId, UUID holderId, UUID assetId, UUID investorId, String errorCode)
        implements AuditableEvent {

    public String eventType()   { return "REGISTER_STATEMENT_DELIVERY_FAILED"; }
    public String subjectType() { return "RegisterStatement"; }
    public UUID   subjectId()   { return statementId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() {
        return Map.of("holderId", holderId.toString(), "assetId", assetId.toString(),
                "investorId", investorId.toString(), "errorCode", errorCode);
    }
}
