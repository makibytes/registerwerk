package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.HolderSyncStatus;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * Refuses corporate-action entitlement work on a register that is not reconciled with the chain
 * (T2-18). Applies only to assets with a deployment — their register is chain-derived by
 * {@code HolderDataService}; a purely off-chain register is authoritative by itself.
 *
 * <p>Refused while the asset's holder sync is {@code BLOCKED}, or while its last successful sync
 * is missing or older than the start of the record date (in the same zone the daily job derives
 * "today" from). Also exposes {@code registerwerk_corporate_action_snapshot_blocked} for the
 * {@code CorporateActionSnapshotBlocked} alert.
 */
@Component
class RegisterFreshnessGate {

    private final AssetRepository assetRepository;
    private final AssetDeploymentRepository deploymentRepository;

    RegisterFreshnessGate(AssetRepository assetRepository,
                          AssetDeploymentRepository deploymentRepository,
                          CorporateActionRepository corporateActionRepository,
                          MeterRegistry meterRegistry) {
        this.assetRepository = assetRepository;
        this.deploymentRepository = deploymentRepository;
        Gauge.builder("registerwerk_corporate_action_snapshot_blocked",
                        corporateActionRepository, repo -> repo.countByStatus(CorporateAction.Status.SNAPSHOT_BLOCKED))
                .description("Corporate actions whose record-date snapshot is refused because the register is not reconciled")
                .register(meterRegistry);
    }

    /**
     * @param recordDate the entitlement date the register must be reconciled for; null checks the
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
            Instant recordStart = recordDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
            if (lastOk == null || lastOk.isBefore(recordStart)) {
                return Optional.of("Register last reconciled " + (lastOk != null ? "at " + lastOk : "never")
                        + ", before record date " + recordDate + " — refresh holders before the snapshot.");
            }
        }
        return Optional.empty();
    }
}
