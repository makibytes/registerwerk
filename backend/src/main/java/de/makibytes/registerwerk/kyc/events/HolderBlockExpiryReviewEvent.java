package de.makibytes.registerwerk.kyc.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * A §16 eWpG Sperrvermerk passed its {@code expires_at} but its type is not configured for automatic
 * expiry (6-25, T6-11): it moves to EXPIRY_REVIEW and keeps blocking until compliance lifts it
 * through the normal step-up + second-approver lift. Published by the daily expiry job.
 */
public record HolderBlockExpiryReviewEvent(UUID holderBlockId, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "HOLDER_BLOCK_EXPIRY_REVIEW"; }
    public String subjectType() { return "HolderBlock"; }
    public UUID   subjectId()   { return holderBlockId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
