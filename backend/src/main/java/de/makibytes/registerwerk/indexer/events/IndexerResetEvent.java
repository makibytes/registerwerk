package de.makibytes.registerwerk.indexer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** An operator manually reset an indexer out of {@code ERROR} status — see
 *  {@code IndexerAdminService.reset}, the only write path for {@code indexer_state.status} other
 *  than the sync services themselves. */
public record IndexerResetEvent(
        UUID indexerStateId, UUID actorId, String actorRole, String indexerType, boolean fullResync)
        implements AuditableEvent {
    public String eventType()   { return "INDEXER_RESET"; }
    public String subjectType() { return "IndexerState"; }
    public UUID   subjectId()   { return indexerStateId; }
    public Map<String, Object> payload() {
        return Map.of("indexerType", indexerType, "fullResync", fullResync);
    }
}
