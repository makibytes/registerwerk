package de.makibytes.registerwerk.finality.api;

import de.makibytes.registerwerk.deployment.api.TokenStandard;

import java.util.UUID;

/**
 * Read port for the finality policy resolution chain — the level a {@link GatedOperation} must
 * reach for a specific asset before it is allowed to proceed. No caller exists yet ({@code
 * FinalityGate} is a later phase); this is the read side other modules will call once it does.
 *
 * <p>Resolution order, most specific wins: asset override → asset-scoped assignment →
 * token-standard-scoped assignment → global assignment → compiled-in default
 * ({@code FinalityPolicyDefaults} — code, not a seed row, so a fresh database, an integration
 * test, and a failed migration all resolve identically).
 *
 * <p>{@code tokenStandard} is a caller-supplied parameter, not looked up internally, so that
 * {@code finality} never needs to import {@code asset.api} — the caller already has the
 * {@code Asset} loaded in every real call site, and {@code asset} already depends on
 * {@code finality} (for chain-effect journalling), so the reverse import would be a module cycle.
 */
public interface FinalityPolicyService {

    /**
     * @param operation    the action being gated
     * @param assetId      the asset the action applies to — every {@link GatedOperation} in this
     *                     system is asset-scoped in practice, so this is required, not optional
     * @param tokenStandard the asset's token standard, for the token-standard-scoped rung of the
     *                      resolution chain
     * @return the {@link FinalityLevel} that must be reached before {@code operation} may proceed
     *         for {@code assetId}, after resolving the full chain and clamping to any hard floor
     */
    FinalityLevel requiredLevel(GatedOperation operation, UUID assetId, TokenStandard tokenStandard);
}
