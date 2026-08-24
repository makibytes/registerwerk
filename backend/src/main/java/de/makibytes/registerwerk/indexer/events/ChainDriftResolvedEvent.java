package de.makibytes.registerwerk.indexer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Fired when an operator closes a chain-drift case (a detected registry-vs-chain balance
 * divergence, eWpG §16 / KryptoFAV §6) — the resolution notes explaining whether the registry
 * or the chain was corrected, or why the divergence was accepted as expected, belong in the
 * tamper-evident audit log alongside the mutable {@code ChainDriftEvent} row.
 */
public record ChainDriftResolvedEvent(
        UUID driftEventId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "CHAIN_DRIFT_RESOLVED"; }
    public String subjectType() { return "ChainDriftEvent"; }
    public UUID   subjectId()   { return driftEventId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
