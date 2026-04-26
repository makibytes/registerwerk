package de.makibytes.registerwerk.application.indexer;

import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetDeploymentRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetRepository;
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
public class HolderDataService {

    private static final Logger log = LoggerFactory.getLogger(HolderDataService.class);

    private final AssetRepository assetRepository;
    private final AssetDeploymentRepository deploymentRepository;

    public HolderDataService(AssetRepository assetRepository,
                             AssetDeploymentRepository deploymentRepository) {
        this.assetRepository = assetRepository;
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
