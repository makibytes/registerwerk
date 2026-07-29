package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record AssetTokenAdminGrantedEvent(
        UUID grantId, UUID entityId, UUID assetId, UUID actorId, String actorRole,
        String walletAddress, String legalBasis, String eligibilityBasis, UUID dualControlApproverId
) implements AuditableEvent {
    public String eventType()   { return "ASSET_TOKEN_ADMIN_GRANTED"; }
    public String subjectType() { return "AssetTokenAdminGrant"; }
    public UUID   subjectId()   { return grantId; }
    public Map<String, Object> payload() {
        return Map.of(
                "entityId", entityId.toString(),
                "assetId", assetId != null ? assetId.toString() : "ENTITY_WIDE",
                "walletAddress", walletAddress,
                "legalBasis", legalBasis,
                "eligibilityBasis", eligibilityBasis
        );
    }
}
