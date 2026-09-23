package de.makibytes.registerwerk.indexer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** Published when a previously BLOCKED asset's holder sync reconciles again (T2-18). System-attributed. */
public record HolderSyncRestoredEvent(UUID assetId) implements AuditableEvent {

    public String eventType()   { return "HOLDER_SYNC_RESTORED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return Map.of(); }
}
