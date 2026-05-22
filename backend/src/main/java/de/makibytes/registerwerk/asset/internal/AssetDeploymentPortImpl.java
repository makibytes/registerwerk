package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.blockchain.api.AssetDeploymentPort;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Asset-module implementation of blockchain's AssetDeploymentPort.
 * Breaks the asset ↔ blockchain modulith cycle: blockchain declares the port,
 * asset provides the implementation. blockchain depends on the port interface only.
 */
@Component
class AssetDeploymentPortImpl implements AssetDeploymentPort {

    private final AssetDeploymentRepository repository;

    AssetDeploymentPortImpl(AssetDeploymentRepository repository) {
        this.repository = repository;
    }

    @Override
    public DeploymentRef findById(java.util.UUID deploymentId) {
        AssetDeployment dep = repository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        return new DeploymentRef(dep.getId(), dep.getAssetId(), dep.getChain(),
                dep.getNetwork() != null ? dep.getNetwork().name() : null,
                dep.getContractAddress());
    }
}
