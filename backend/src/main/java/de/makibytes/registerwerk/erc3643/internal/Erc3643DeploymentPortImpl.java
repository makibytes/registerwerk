package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.erc3643.api.Erc3643DeploymentPort;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Delegates ERC-3643 deployment requests to the appropriate internal service.
 */
@Service
public class Erc3643DeploymentPortImpl implements Erc3643DeploymentPort {

    private final Erc3643DeploymentService erc3643DeploymentService;
    private final ConfidentialErc3643Service confidentialErc3643Service;

    public Erc3643DeploymentPortImpl(
            Erc3643DeploymentService erc3643DeploymentService,
            ConfidentialErc3643Service confidentialErc3643Service) {
        this.erc3643DeploymentService = erc3643DeploymentService;
        this.confidentialErc3643Service = confidentialErc3643Service;
    }

    @Override
    public CompletableFuture<String> deployStandard(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        return erc3643DeploymentService.deploy(assetId, chain, ownerAddress);
    }

    @Override
    public CompletableFuture<String> deployConfidential(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        return confidentialErc3643Service.deploy(assetId, chain, ownerAddress);
    }
}
