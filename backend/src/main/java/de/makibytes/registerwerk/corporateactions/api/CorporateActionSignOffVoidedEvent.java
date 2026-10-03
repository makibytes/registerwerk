package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * The issuer attestation and/or operator confirmation of a corporate action's payout were voided (Wave 0b C6): the
 * computed amounts were re-snapshotted, or no longer match the digest the parties signed. The payout does not start
 * until both parties sign the amounts as they now are. System-attributed; the reason names the cause.
 */
public record CorporateActionSignOffVoidedEvent(UUID corporateActionId, String reason, boolean issuerHadAttested,
                                                boolean operatorHadConfirmed) implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_SIGN_OFF_VOIDED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public UUID   actorId()     { return null; }
    @Override public String actorRole()   { return "SYSTEM"; }
    @Override public Map<String, Object> payload() {
        return Map.of("reason", reason, "issuerHadAttested", issuerHadAttested,
                "operatorHadConfirmed", operatorHadConfirmed);
    }
}
