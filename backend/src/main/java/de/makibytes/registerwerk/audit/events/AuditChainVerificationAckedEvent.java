package de.makibytes.registerwerk.audit.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** A broken audit-chain verification verdict was acknowledged under dual control (7B-04). */
public record AuditChainVerificationAckedEvent(UUID verificationId, UUID actorId, String role, String note)
        implements AuditableEvent {
    public String eventType()   { return "AUDIT_CHAIN_VERIFICATION_ACKED"; }
    public String subjectType() { return "AuditChainVerification"; }
    public UUID   subjectId()   { return verificationId; }
    public UUID   actorId()     { return actorId; }
    public String actorRole()   { return role; }
    public Map<String, Object> payload() { return Map.of("noteLength", note == null ? 0 : note.length()); }
}
