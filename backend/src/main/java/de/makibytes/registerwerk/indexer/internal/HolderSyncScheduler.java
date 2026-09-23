package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.deployment.api.AssetLookupPort;

import de.makibytes.registerwerk.chain.api.SyncConfig;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.indexer.api.HolderDataService;
import de.makibytes.registerwerk.indexer.api.UnmappedHolderIdentityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Scheduler for automatic token holder data synchronization from blockchain.
 * Syncs all active issuances with minted tokens at regular intervals.
 *
 * <p>Activation: the previous {@code @ConditionalOnProperty(havingValue = "^[1-9].*")} compared the
 * property against that regex as a <em>literal string</em> (and under a camelCase name), so this
 * bean was never created and nothing refreshed the register periodically. T2-18's corporate-action
 * freshness gate depends on it running, hence a real numeric condition.
 *
 * <p>Coverage: every run pages through <em>all</em> eligible assets, {@code batchSize} at a time.
 * It used to sync only the first {@code batchSize} assets in a fixed order, so beyond that the
 * tail was never refreshed and the freshness gate kept its corporate actions SNAPSHOT_BLOCKED.
 */
@Service
@ConditionalOnExpression("${registerwerk.sync.auto-refresh-interval-minutes:0} > 0")
public class HolderSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(HolderSyncScheduler.class);

    private final AssetLookupPort assetLookupPort;
    private final AssetDeploymentRepository deploymentRepository;
    private final HolderDataService holderDataService;
    private final SyncConfig syncConfig;
    private volatile boolean syncInProgress = false;

    public HolderSyncScheduler(
            AssetLookupPort assetLookupPort,
            AssetDeploymentRepository deploymentRepository,
            HolderDataService holderDataService,
            SyncConfig syncConfig) {
        this.assetLookupPort = assetLookupPort;
        this.deploymentRepository = deploymentRepository;
        this.holderDataService = holderDataService;
        this.syncConfig = syncConfig;
    }

    // syncInProgress is a per-JVM guard only — harmless as a fast local re-entrancy check,
    // but it does NOT prevent two backend instances from running this concurrently. The
    // @SchedulerLock below is what actually serializes it across instances.
    @SchedulerLock(name = "holderSyncScheduler", lockAtMostFor = "PT30M")
    @Scheduled(fixedDelayString = "#{@syncConfig.autoRefreshIntervalMinutes * 60000}", initialDelayString = "60000")
    public void syncAllActiveIssuances() {
        if (!syncConfig.isAutoRefreshEnabled() || syncInProgress) return;

        syncInProgress = true;
        try {
            if (syncConfig.isLogSyncOperations()) {
                log.info("Starting automatic token holder sync...");
            }

            List<AssetLookupPort.AssetInfo> activeIssuances = assetLookupPort.findAll().stream()
                    .filter(this::shouldSyncIssuance)
                    .toList();
            int pageSize = Math.max(1, syncConfig.getBatchSize());

            for (int from = 0; from < activeIssuances.size(); from += pageSize) {
                List<AssetLookupPort.AssetInfo> page =
                        activeIssuances.subList(from, Math.min(from + pageSize, activeIssuances.size()));
                for (AssetLookupPort.AssetInfo asset : page) {
                    syncOne(asset);
                }
                if (syncConfig.isLogSyncOperations() && activeIssuances.size() > pageSize) {
                    log.debug("Holder sync progress: {}/{} assets", from + page.size(), activeIssuances.size());
                }
            }

            if (syncConfig.isLogSyncOperations()) {
                log.info("Automatic token holder sync completed. Synced {} assets.", activeIssuances.size());
            }
        } finally {
            syncInProgress = false;
        }
    }

    private void syncOne(AssetLookupPort.AssetInfo asset) {
        try {
            holderDataService.syncHoldersFromBlockchain(asset.id());
            if (syncConfig.isLogSyncOperations()) {
                log.debug("Synced holders for asset: {}", asset.id());
            }
        } catch (UnmappedHolderIdentityException e) {
            // Already persisted as holder_sync_status = BLOCKED (banner, metric, alert,
            // corporate-action snapshot gate) by HolderDataService — not a silent skip.
            log.error("Register BLOCKED for asset {}: {}", asset.id(), e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to sync holders for asset {}: {}", asset.id(), e.getMessage());
        }
    }

    private boolean shouldSyncIssuance(AssetLookupPort.AssetInfo asset) {
        // Only sync issued assets
        if (!"ISSUED".equals(asset.status())) return false;
        // Only sync assets that have at least one deployment
        return !deploymentRepository.findByAssetId(asset.id()).isEmpty();
    }
}
