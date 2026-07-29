package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Published when a holder is entered in the register in their favour
 * (§19(2) no. 1 — triggers an INITIAL_ENTRY register statement).
 *
 * <p>{@code correlationId} links this audit entry to the operation that caused it — e.g. the
 * {@code TradeExecution} id for a trade-settlement entry, so buyer/seller audit rows can be
 * traced back to the originating trade. Null for entries created directly by an operator/issuer
 * action ({@code HolderService.addHolder}/{@code addSingleEntryHolder}), which have no broader
 * operation to correlate with.
 */
public record HolderEnteredEvent(UUID holderId, UUID actorId, String actorRole, UUID correlationId)
        implements AuditableEvent {

    public HolderEnteredEvent(UUID holderId, UUID actorId, String actorRole) {
        this(holderId, actorId, actorRole, null);
    }

    public String eventType()   { return "HOLDER_ENTERED"; }
    public String subjectType() { return "AssetHolder"; }
    public UUID   subjectId()   { return holderId; }
    public Map<String, Object> payload() { return Map.of(); }

    @Override
    public UUID correlationId() { return correlationId; }
}
