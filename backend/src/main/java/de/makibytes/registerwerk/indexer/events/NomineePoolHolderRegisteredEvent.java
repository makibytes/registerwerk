package de.makibytes.registerwerk.indexer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Published when a pool contract (lending market, DvP escrow, desk inventory, facility) is
 * entered into an asset's register as a {@code NOMINEE_POOL} holder (T2-18). {@code actorId} is
 * null for the automatic registration that follows a lending-market registration.
 */
public record NomineePoolHolderRegisteredEvent(
        UUID holderId, UUID assetId, String walletAddress, String poolKind, UUID investorId,
        UUID actorId, String actorRole) implements AuditableEvent {

    public String eventType()   { return "NOMINEE_POOL_HOLDER_REGISTERED"; }
    public String subjectType() { return "AssetHolder"; }
    public UUID   subjectId()   { return holderId; }
    public Map<String, Object> payload() {
        return Map.of("assetId", assetId.toString(), "walletAddress", walletAddress,
                "poolKind", poolKind, "investorId", investorId.toString());
    }
}
