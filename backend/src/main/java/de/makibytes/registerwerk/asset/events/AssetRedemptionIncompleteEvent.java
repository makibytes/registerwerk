package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Redemption left active register entries unburnt on-chain (T3-01): a holder without a settled
 * payout, a nominee pool, a failed burn, or a standard with no automated burn. The asset is
 * REDEEMED in the register but tokens are still live, so an operator must follow up. Published by
 * {@code AssetRedemptionListener}; the details list the wallets and why each was left.
 */
public record AssetRedemptionIncompleteEvent(UUID assetId, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "ASSET_REDEMPTION_INCOMPLETE"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
