package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.events.AssetRedemptionIncompleteEvent;
import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionView;
import de.makibytes.registerwerk.blockchain.events.BlockchainTxStatusEvent;
import de.makibytes.registerwerk.deployment.api.IndexedTransferLookup;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Finishes a redemption on the chain OUTCOME (Wave 0b C7). A burn the redemption dispatched is {@code CONFIRMED} only
 * when its transaction is {@code SUCCESS} (final receipt under the chain's finality model) AND the indexer has
 * recorded the BURN transfer of that wallet for that amount as {@code FINALIZED}. {@code FAILED} / {@code REPLACED}
 * mean the burn definitively did not happen: the burn row is FAILED and an {@link AssetRedemptionIncompleteEvent}
 * (audit + operator alert) names it; the asset stays {@code REDEMPTION_PENDING} and the redemption can be resumed.
 * {@code TIMEOUT} is not a failure (the transaction may still be mined), so the burn keeps waiting.
 *
 * <p>The asset becomes {@code REDEEMED} (via {@link AssetLifecycleService#completeRedemption}) once every burn of it is
 * confirmed. Triggered by the {@link BlockchainTxStatusEvent} the transaction poller publishes and by a periodic sweep.
 */
@Component
class RedemptionBurnFinalizer {

    private static final Logger log = LoggerFactory.getLogger(RedemptionBurnFinalizer.class);

    private final AssetRedemptionBurnRepository burns;
    private final AssetRepository assets;
    private final BlockchainApi blockchain;
    private final IndexedTransferLookup indexed;
    private final AssetLifecycleService lifecycle;
    private final ApplicationEventPublisher events;
    private final IsolatedTransactionExecutor isolated;

    RedemptionBurnFinalizer(AssetRedemptionBurnRepository burns, AssetRepository assets, BlockchainApi blockchain,
                            IndexedTransferLookup indexed, AssetLifecycleService lifecycle,
                            ApplicationEventPublisher events, IsolatedTransactionExecutor isolated,
                            MeterRegistry meterRegistry) {
        this.burns = burns;
        this.assets = assets;
        this.blockchain = blockchain;
        this.indexed = indexed;
        this.lifecycle = lifecycle;
        this.events = events;
        this.isolated = isolated;
        Gauge.builder("registerwerk_asset_redemption_burn_failed", burns,
                        r -> r.countByStatus(AssetRedemptionBurn.Status.FAILED))
                .description("Redemption burns that definitively failed: the asset stays REDEMPTION_PENDING with live tokens")
                .register(meterRegistry);
        Gauge.builder("registerwerk_asset_redemption_burn_pending", burns,
                        r -> r.countByStatus(AssetRedemptionBurn.Status.SUBMITTED))
                .description("Redemption burns submitted but not final yet")
                .register(meterRegistry);
    }

    @ApplicationModuleListener
    void onTransactionStatus(BlockchainTxStatusEvent event) {
        Object hash = event.details() == null ? null : event.details().get("txHash");
        if (hash == null) {
            return;
        }
        blockchain.findByTxHash(String.valueOf(hash))
                .flatMap(tx -> burns.findByTxId(tx.id()))
                .ifPresent(burn -> {
                    reconcileBurn(burn.getId());
                    tryComplete(burn.getAssetId());
                });
    }

    @SchedulerLock(name = "redemptionBurnFinalizer", lockAtMostFor = "PT4M")
    @Scheduled(fixedDelayString = "${registerwerk.redemption.burn-sweep-ms:30000}",
            initialDelayString = "${registerwerk.redemption.burn-sweep-initial-ms:60000}")
    public void sweep() {
        for (AssetRedemptionBurn submitted : burns.findByStatus(AssetRedemptionBurn.Status.SUBMITTED)) {
            UUID burnId = submitted.getId();
            try {
                isolated.run(() -> reconcileBurn(burnId));
            } catch (Exception e) {
                log.error("Redemption burn reconciliation failed for burn {}: {}", burnId, e.getMessage(), e);
            }
        }
        for (Asset asset : assets.findByStatus(AssetStatus.REDEMPTION_PENDING)) {
            UUID assetId = asset.getId();
            try {
                isolated.run(() -> tryComplete(assetId));
            } catch (Exception e) {
                log.error("Redemption completion check failed for asset {}: {}", assetId, e.getMessage(), e);
            }
        }
    }

    /** Idempotent: acts only on a SUBMITTED burn whose transaction has an outcome. */
    void reconcileBurn(UUID burnId) {
        AssetRedemptionBurn burn = burns.findByIdForUpdate(burnId).orElse(null);
        if (burn == null || burn.getStatus() != AssetRedemptionBurn.Status.SUBMITTED || burn.getTxId() == null) {
            return;
        }
        Optional<BlockchainTransactionView> found = blockchain.findTransaction(burn.getTxId());
        if (found.isEmpty()) {
            log.warn("Redemption burn {} waits for transaction {} which is not tracked", burnId, burn.getTxId());
            return;
        }
        BlockchainTransactionView tx = found.get();
        switch (tx.status()) {
            case "SUCCESS" -> {
                if (burnIsIndexed(burn, tx)) {
                    burn.setStatus(AssetRedemptionBurn.Status.CONFIRMED);
                    burn.setConfirmedAt(Instant.now());
                    burns.save(burn);
                    log.info("Redemption burn confirmed: asset={} deployment={} wallet={} amount={}",
                            burn.getAssetId(), burn.getDeploymentId(), burn.getWalletAddress(), burn.getAmount());
                } else {
                    log.info("Redemption burn {}: transaction {} succeeded, its BURN transfer is not indexed as FINALIZED yet",
                            burnId, tx.txHash());
                }
            }
            case "FAILED", "REPLACED" -> {
                String reason = "burn " + tx.txHash() + " " + tx.status()
                        + (tx.errorMessage() != null ? ": " + tx.errorMessage() : "");
                burn.setStatus(AssetRedemptionBurn.Status.FAILED);
                burn.setFailureReason(reason);
                burns.save(burn);
                reportFailed(burn, reason);
            }
            default -> log.debug("Redemption burn {}: transaction {} is {} - still in flight", burnId, tx.txHash(), tx.status());
        }
    }

    private boolean burnIsIndexed(AssetRedemptionBurn burn, BlockchainTransactionView tx) {
        String hash = tx.minedTxHash() != null ? tx.minedTxHash() : tx.txHash();
        return indexed.finalizedTransfers(hash).stream().anyMatch(t -> t.burn()
                && t.fromAddress() != null && t.fromAddress().equalsIgnoreCase(burn.getWalletAddress())
                && t.amount() != null && t.amount().compareTo(burn.getAmount()) == 0);
    }

    /** REDEEMED once every burn of the asset is confirmed. */
    void tryComplete(UUID assetId) {
        List<AssetRedemptionBurn> all = burns.findByAssetId(assetId);
        if (all.isEmpty() || !all.stream().allMatch(b -> b.getStatus() == AssetRedemptionBurn.Status.CONFIRMED)) {
            return;
        }
        lifecycle.completeRedemption(assetId, all.size());
    }

    private void reportFailed(AssetRedemptionBurn burn, String reason) {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("walletAddress", burn.getWalletAddress());
        failed.put("deploymentId", String.valueOf(burn.getDeploymentId()));
        failed.put("amount", burn.getAmount().toPlainString());
        failed.put("reason", reason);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("unburnt", List.of(failed));
        details.put("note", "the asset stays REDEMPTION_PENDING; resume the redemption to re-dispatch failed burns");
        events.publishEvent(new AssetRedemptionIncompleteEvent(burn.getAssetId(), details));
        log.error("REDEMPTION BURN FAILED: asset={} wallet={} - {}", burn.getAssetId(), burn.getWalletAddress(), reason);
    }
}
