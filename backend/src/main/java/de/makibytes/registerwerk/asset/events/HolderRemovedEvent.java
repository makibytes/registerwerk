package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Published when a holder record is removed from the register ({@code HolderService#removeHolder}).
 *
 * <p>The removal is a soft-delete (§16 eWpG register entries must not simply vanish — a hard
 * delete conflicts with retention/tamper-evidence obligations): the {@code AssetHolder} row is
 * retained with {@code removedAt} set, not deleted. This event is what makes the removal
 * itself tamper-evident in the audit hash chain, rather than leaving only an SLF4J log line
 * as a trace.
 */
public record HolderRemovedEvent(UUID holderId, UUID actorId, String actorRole) implements AuditableEvent {
    public String eventType()   { return "HOLDER_REMOVED"; }
    public String subjectType() { return "AssetHolder"; }
    public UUID   subjectId()   { return holderId; }
    public Map<String, Object> payload() { return Map.of(); }
}
