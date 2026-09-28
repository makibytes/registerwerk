package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Asset moved to REDEEMED (T3-01: REGISTRY_ADMIN with step-up + 4-eyes only). Carries the stated
 * legal basis/reference, the second approver, and — for bonds — the settled REDEMPTION/CALL
 * corporate action whose paid entries are the only wallets {@code AssetRedemptionListener} burns.
 */
public record AssetRedeemedEvent(
        UUID assetId, UUID actorId, String actorRole,
        String legalBasis, String reference, UUID dualControlApproverId, UUID retirementActionId)
        implements AuditableEvent {

    public AssetRedeemedEvent(UUID assetId, UUID actorId, String actorRole) {
        this(assetId, actorId, actorRole, null, null, null, null);
    }

    public String eventType()   { return "ASSET_REDEEMED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public Map<String, Object> payload() {
        Map<String, Object> p = new HashMap<>();
        if (legalBasis != null) p.put("legalBasis", legalBasis);
        if (reference != null) p.put("reference", reference);
        if (retirementActionId != null) p.put("retirementCorporateActionId", retirementActionId.toString());
        return p;
    }
}
