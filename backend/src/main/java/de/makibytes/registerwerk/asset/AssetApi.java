package de.makibytes.registerwerk.asset;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public API for the asset module.
 */
public interface AssetApi {

    Optional<Asset> findAsset(UUID id);

    Optional<AssetDeployment> findDeployment(UUID id);

    List<AssetDeployment> findDeploymentsByAsset(UUID assetId);

    Optional<AssetHolder> findHolder(UUID id);

    List<AssetHolder> findHoldersByInvestor(UUID investorId);
}
