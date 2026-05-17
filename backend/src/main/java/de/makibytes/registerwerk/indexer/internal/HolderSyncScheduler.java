package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.config.SyncConfig;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Scheduler for automatic token holder data synchronization from blockchain.
 * Syncs all active issuances with minted tokens at regular intervals.
 */
@Service
@ConditionalOnProperty(
    prefix = "registerwerk.sync",
    name = "autoRefreshIntervalMinutes",
    havingValue = "^[1-9].*",
    matchIfMissing = false
)
public class HolderSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(HolderSyncScheduler.class);

    private final AssetRepository assetRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final HolderDataService holderDataService;
    private final SyncConfig syncConfig;
    private volatile boolean syncInProgress = false;

    public HolderSyncScheduler(
            AssetRepository assetRepository,
            AssetDeploymentRepository deploymentRepository,
            HolderDataService holderDataService,
            SyncConfig syncConfig) {
        this.assetRepository = assetRepository;
        this.deploymentRepository = deploymentRepository;
        this.holderDataService = holderDataService;
        this.syncConfig = syncConfig;
    }

    @Scheduled(fixedDelayString = "#{@syncConfig.autoRefreshIntervalMinutes * 60000}", initialDelayString = "60000")
    public void syncAllActiveIssuances() {
        if (!syncConfig.isAutoRefreshEnabled() || syncInProgress) return;

        syncInProgress = true;
        try {
            if (syncConfig.isLogSyncOperations()) {
                log.info("Starting automatic token holder sync...");
            }

            List<Asset> activeIssuances = assetRepository.findAll().stream()
                    .filter(this::shouldSyncIssuance)
                    .limit(syncConfig.getBatchSize())
                    .toList();

            for (Asset asset : activeIssuances) {
                try {
                    holderDataService.syncHoldersFromBlockchain(asset.getId());
                    if (syncConfig.isLogSyncOperations()) {
                        log.debug("Synced holders for asset: {}", asset.getId());
                    }
                } catch (Exception e) {
                    log.warn("Failed to sync holders for asset {}: {}", asset.getId(), e.getMessage());
                }
            }

            if (syncConfig.isLogSyncOperations()) {
                log.info("Automatic token holder sync completed. Synced {} assets.", activeIssuances.size());
            }
        } finally {
            syncInProgress = false;
        }
    }

    private boolean shouldSyncIssuance(Asset asset) {
        // Only sync issued assets
        if (asset.getStatus() != AssetStatus.ISSUED) return false;
        // Only sync assets that have at least one deployment
        return !deploymentRepository.findByAssetId(asset.getId()).isEmpty();
    }
}
