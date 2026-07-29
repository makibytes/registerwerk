package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrant;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared between the asset-scoped and entity-wide grant controllers. {@code entityId} is
 * only required (and read) on the asset-scoped endpoint, where the target asset comes from
 * the path and the grantee entity must be named in the body; on the entity-wide endpoint
 * the grantee is already the path variable, so {@code entityId} here is ignored (not
 * {@code @NotNull} — the entity-wide caller need not repeat it in the body).
 */
public record AssetTokenAdminGrantRequest(
        UUID entityId,
        @NotBlank String walletAddress,
        UUID chainConfigId,
        @NotBlank String legalBasis,
        Instant expiresAt
) {
    /** @param assetId null for an entity-wide grant, the target asset otherwise. */
    public AssetTokenAdminGrant toEntity(UUID assetId) {
        AssetTokenAdminGrant g = new AssetTokenAdminGrant();
        g.setAssetId(assetId);
        g.setEntityId(entityId);
        g.setWalletAddress(walletAddress);
        g.setChainConfigId(chainConfigId);
        g.setLegalBasis(legalBasis);
        g.setExpiresAt(expiresAt);
        return g;
    }
}
