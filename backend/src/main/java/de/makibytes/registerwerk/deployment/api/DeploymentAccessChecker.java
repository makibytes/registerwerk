package de.makibytes.registerwerk.deployment.api;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Method-security guard for REST paths that contain both an asset and one of its deployments.
 * Returning false for missing deployments avoids disclosing whether a foreign child ID exists.
 */
@Component("deploymentAccessChecker")
public class DeploymentAccessChecker {

    private final AssetDeploymentRepository repository;

    public DeploymentAccessChecker(AssetDeploymentRepository repository) {
        this.repository = repository;
    }

    public boolean belongsToAsset(UUID deploymentId, UUID assetId) {
        return deploymentId != null && assetId != null
                && repository.findByIdAndAssetId(deploymentId, assetId).isPresent();
    }
}
