package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.deployment.api.AssetLookupPort;

import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Stub service for synchronizing token holder data from blockchain.
 * Full on-chain sync logic is not yet implemented.
 */
@Service
public class HolderDataService implements de.makibytes.registerwerk.indexer.IndexerApi {

    private static final Logger log = LoggerFactory.getLogger(HolderDataService.class);

    private final AssetLookupPort assetLookupPort;
    private final AssetDeploymentRepository deploymentRepository;

    public HolderDataService(AssetLookupPort assetLookupPort,
                             AssetDeploymentRepository deploymentRepository) {
        this.assetLookupPort = assetLookupPort;
        this.deploymentRepository = deploymentRepository;
    }

    /** Synchronize holders for a specific asset from blockchain (stub). */
    @Transactional
    public void syncHoldersFromBlockchain(UUID assetId) {
        var deployments = deploymentRepository.findByAssetId(assetId);
        log.info("Holder sync for asset={}: {} deployments (on-chain sync not yet implemented)",
                assetId, deployments.size());
    }

    /** Manual refresh triggered by user action (stub). */
    @Transactional
    public void manualRefreshIssuance(String assetId) {
        syncHoldersFromBlockchain(java.util.UUID.fromString(assetId));
    }
}
