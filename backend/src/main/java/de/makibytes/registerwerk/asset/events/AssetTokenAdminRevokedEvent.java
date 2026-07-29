package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record AssetTokenAdminRevokedEvent(
        UUID grantId, UUID entityId, UUID assetId, UUID actorId, String actorRole, String reason,
        UUID dualControlApproverId
) implements AuditableEvent {
    public String eventType()   { return "ASSET_TOKEN_ADMIN_REVOKED"; }
    public String subjectType() { return "AssetTokenAdminGrant"; }
    public UUID   subjectId()   { return grantId; }
    public Map<String, Object> payload() {
        return Map.of(
                "entityId", entityId.toString(),
                "assetId", assetId != null ? assetId.toString() : "ENTITY_WIDE",
                "reason", reason != null ? reason : ""
        );
    }
}
