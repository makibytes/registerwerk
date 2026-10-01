package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** A party or the operator added an evidence note to a trade (text is in {@code trade_execution_note}). */
public record TradeNoteAddedEvent(UUID executionId, UUID actorId, String actorRole, UUID noteId)
        implements AuditableEvent {
    public String eventType()   { return "TRADE_NOTE_ADDED"; }
    public String subjectType() { return "TradeExecution"; }
    public UUID   subjectId()   { return executionId; }
    public Map<String, Object> payload() { return Map.of("noteId", noteId.toString()); }
}
