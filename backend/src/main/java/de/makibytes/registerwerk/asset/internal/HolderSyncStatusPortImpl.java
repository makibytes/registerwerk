package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.HolderSyncStatus;
import de.makibytes.registerwerk.deployment.api.HolderSyncStatusPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists holder-sync reconciliation state on {@code asset} (T2-18) and exposes
 * {@code registerwerk_holder_sync_blocked_assets} — the live count of BLOCKED assets that
 * {@code monitoring/alerts/registerwerk.yml#HolderSyncBlocked} fires on.
 *
 * <p>Joins the caller's transaction ({@code HolderDataService} runs with
 * {@code noRollbackFor = UnmappedHolderIdentityException}), so a BLOCKED write survives the
 * exception that follows it.
 */
@Component
class HolderSyncStatusPortImpl implements HolderSyncStatusPort {

    private final AssetRepository assetRepository;

    HolderSyncStatusPortImpl(AssetRepository assetRepository, MeterRegistry meterRegistry) {
        this.assetRepository = assetRepository;
        Gauge.builder("registerwerk_holder_sync_blocked_assets",
                        assetRepository, repo -> repo.countByHolderSyncStatus(HolderSyncStatus.BLOCKED))
                .description("Assets whose chain-derived register is BLOCKED (unmapped wallets, stale register)")
                .register(meterRegistry);
    }

    @Override
    @Transactional
    public boolean markReconciled(UUID assetId, Instant at) {
        boolean wasBlocked = assetRepository.findHolderSyncStatus(assetId)
                .map(HolderSyncStatus.BLOCKED::equals).orElse(false);
        assetRepository.markHolderSyncReconciled(assetId, at);
        return wasBlocked;
    }

    @Override
    @Transactional
    public boolean markBlocked(UUID assetId, Instant at, List<String> unmappedWallets, String reason) {
        String wallets = String.join(",", unmappedWallets);
        boolean changed = assetRepository.findHolderSyncStatus(assetId)
                .map(status -> status != HolderSyncStatus.BLOCKED
                        || !Objects.equals(wallets, assetRepository.findHolderSyncUnmappedWallets(assetId).orElse(null)))
                .orElse(false);
        assetRepository.markHolderSyncBlocked(assetId, at, reason, wallets);
        return changed;
    }
}
