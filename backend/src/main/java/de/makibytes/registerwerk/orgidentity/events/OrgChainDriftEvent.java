package de.makibytes.registerwerk.orgidentity.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** Emitted when the DB mirror disagrees with onchain state (e.g. after direct org-admin txs). */
public record OrgChainDriftEvent(UUID subjectId, String subjectTypeName, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "ORG_CHAIN_DRIFT_DETECTED"; }
    public String subjectType() { return subjectTypeName; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
