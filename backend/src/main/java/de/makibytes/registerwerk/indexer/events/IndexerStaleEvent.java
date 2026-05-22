package de.makibytes.registerwerk.indexer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record IndexerStaleEvent(UUID indexerStateId, UUID actorId, String actorRole, String indexerType, int stalePeriodMinutes) implements AuditableEvent {
    public String eventType()   { return "INDEXER_STALE"; }
    public String subjectType() { return "IndexerState"; }
    public UUID   subjectId()   { return indexerStateId; }
    public Map<String, Object> payload() { return Map.of("indexerType", indexerType, "stalePeriodMinutes", stalePeriodMinutes); }
}
