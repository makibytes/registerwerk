package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.HolderSyncStatus;
import de.makibytes.registerwerk.asset.api.RegisterFreezeGuard;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import io.micrometer.core.instrument.Gauge;
import de.makibytes.registerwerk.shared.RegisterClock;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Refuses corporate-action entitlement work on a register that is not reconciled with the chain
 * (T2-18). Applies only to assets with a deployment — their register is chain-derived by
 * {@code HolderDataService}; a purely off-chain register is authoritative by itself.
 *
 * <p>Refused while the asset's holder sync is {@code BLOCKED}, or while its last successful sync
 * is missing or earlier than the record-date cut-off — the END of the record date in the
 * register's zone ({@link RegisterClock}, T3-06) — plus {@code registerwerk.register.snapshot-sync-margin}
 * (default 30 min), so the indexer has provably looked at the chain past the cut-off. Also exposes {@code registerwerk_corporate_action_snapshot_blocked} for the
 * {@code CorporateActionSnapshotBlocked} alert.
 */
@Component
class RegisterFreshnessGate {

    private final AssetRepository assetRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final RegisterClock registerClock;
    private final Duration syncMargin;

    RegisterFreshnessGate(AssetRepository assetRepository,
                          AssetDeploymentRepository deploymentRepository,
                          CorporateActionRepository corporateActionRepository,
                          MeterRegistry meterRegistry,
                          RegisterClock registerClock,
                          @Value("${registerwerk.register.snapshot-sync-margin:PT30M}") Duration syncMargin) {
        this.assetRepository = assetRepository;
        this.deploymentRepository = deploymentRepository;
        this.registerClock = registerClock;
        this.syncMargin = syncMargin;
        Gauge.builder("registerwerk_corporate_action_snapshot_blocked",
                        corporateActionRepository, repo -> repo.countByStatus(CorporateAction.Status.SNAPSHOT_BLOCKED))
                .description("Corporate actions whose record-date snapshot is refused because the register is not reconciled")
                .register(meterRegistry);
    }

    /** T3-07: a register frozen for (or handed over in) a §§21/22 handover accepts no corporate-action work. */
    boolean isRegisterFrozen(UUID assetId) {
        return assetId != null && assetRepository.findById(assetId)
                .map(a -> a.getStatus() != null && a.getStatus().isRegisterFrozen()).orElse(false);
    }

    /** @throws de.makibytes.registerwerk.shared.InvalidStateTransitionException (409) while frozen */
    void requireRegisterOpen(UUID assetId, String operation) {
        RegisterFreezeGuard.requireOpen(assetRepository, assetId, operation);
    }

    /**
     * @param recordDate the entitlement date the register must be reconciled past; null checks the
     *                   BLOCKED state only
     * @return the operator-facing refusal reason, or empty when the register may be used
     */
    Optional<String> blockedReason(UUID assetId, LocalDate recordDate) {
        if (deploymentRepository.findByAssetId(assetId).isEmpty()) {
            return Optional.empty();
        }
        Asset asset = assetRepository.findById(assetId).orElse(null);
        if (asset == null) {
            return Optional.empty();
        }
        Instant lastOk = asset.getLastSuccessfulHolderSyncAt();
        if (asset.getHolderSyncStatus() == HolderSyncStatus.BLOCKED) {
            return Optional.of("Register not reconciled since " + (lastOk != null ? lastOk : "never")
                    + " — holder sync BLOCKED, unmapped wallets: " + asset.getHolderSyncUnmappedWallets()
                    + ". Map the wallet(s) or register the pool address, then refresh holders.");
        }
        if (recordDate != null) {
            Instant required = registerClock.endOfDay(recordDate).plus(syncMargin);
            if (lastOk == null || lastOk.isBefore(required)) {
                return Optional.of("Register last reconciled " + (lastOk != null ? "at " + lastOk : "never")
                        + ", not yet past the end of record date " + recordDate + " (" + required
                        + ") — refresh holders before the snapshot.");
            }
        }
        return Optional.empty();
    }
}
