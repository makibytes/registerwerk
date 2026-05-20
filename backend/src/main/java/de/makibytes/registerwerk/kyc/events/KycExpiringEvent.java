package de.makibytes.registerwerk.kyc.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Fired when a KYC approval is expired or about to expire (GwG §10 ongoing monitoring).
 * payload.reason: "EXPIRED" or "EXPIRING_SOON"
 */
public record KycExpiringEvent(
        UUID entityId,
        UUID actorId,
        Map<String, Object> details
) implements AuditableEvent {

    @Override public String eventType()    { return "KYC_EXPIRING"; }
    @Override public String subjectType()  { return "LEGAL_ENTITY"; }
    @Override public UUID   subjectId()    { return entityId; }
    @Override public UUID   actorId()      { return actorId; }
    @Override public String actorRole()    { return "SYSTEM"; }
    @Override public Map<String, Object> payload() { return details; }
}
