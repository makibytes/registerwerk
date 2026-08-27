package de.makibytes.registerwerk.indexer.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A registry-vs-chain balance divergence detected by {@link ChainDriftDetectionJob} —
 * eWpG §16 / KryptoFAV §6 requires the register to stay canonical, so any divergence needs a
 * human decision (registry correction, on-chain correction, or a documented explanation) before
 * it can be closed. Previously written by the job and never read by anything: no repository, no
 * controller, no operator UI — an open row could only be seen or closed via direct SQL access.
 */
@Entity
@Table(name = "chain_drift_event")
public class ChainDriftEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "deployment_id", nullable = false)
    private UUID deploymentId;

    @Column(name = "chain_config_id")
    private UUID chainConfigId;

    @Column(name = "wallet_address", nullable = false)
    private String walletAddress;

    @Column(name = "db_balance", nullable = false)
    private BigDecimal dbBalance;

    @Column(name = "onchain_balance", nullable = false)
    private BigDecimal onchainBalance;

    /** Generated column ({@code onchain_balance - db_balance}) — read-only from JPA's side. */
    @Column(name = "delta", insertable = false, updatable = false)
    private BigDecimal delta;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private ChainDriftSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChainDriftStatus status = ChainDriftStatus.OPEN;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();

    /** Immutable — set once at first sighting, unlike {@link #detectedAt} which is refreshed on
     *  every subsequent scan that still finds the divergence. Shows how long a confirmed case has
     *  actually persisted. */
    @Column(name = "first_detected_at", nullable = false)
    private Instant firstDetectedAt = Instant.now();

    /** Whether this divergence has survived a second, independent detection run — see
     *  {@code ChainDriftDetectionJob}'s confirm-on-reconfirmation flow. An unconfirmed row is a
     *  same-run "candidate": excluded from the operator "Open" queue and the open-count gauge,
     *  and silently auto-resolved by the job itself if the divergence disappears before it is
     *  ever confirmed (most likely a transient indexer/chain-stream catch-up window rather than
     *  genuine drift). Once confirmed, only a human can close the case via {@code resolve()}. */
    @Column(name = "confirmed", nullable = false)
    private boolean confirmed = false;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolution_notes")
    private String resolutionNotes;

    @Column(name = "ict_incident_id")
    private UUID ictIncidentId;

    public UUID getId() { return id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public UUID getDeploymentId() { return deploymentId; }
    public void setDeploymentId(UUID deploymentId) { this.deploymentId = deploymentId; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public String getWalletAddress() { return walletAddress; }
    public void setWalletAddress(String walletAddress) { this.walletAddress = walletAddress; }

    public BigDecimal getDbBalance() { return dbBalance; }
    public void setDbBalance(BigDecimal dbBalance) { this.dbBalance = dbBalance; }

    public BigDecimal getOnchainBalance() { return onchainBalance; }
    public void setOnchainBalance(BigDecimal onchainBalance) { this.onchainBalance = onchainBalance; }

    public BigDecimal getDelta() { return delta; }

    public ChainDriftSeverity getSeverity() { return severity; }
    public void setSeverity(ChainDriftSeverity severity) { this.severity = severity; }

    public ChainDriftStatus getStatus() { return status; }
    public void setStatus(ChainDriftStatus status) { this.status = status; }

    public Instant getDetectedAt() { return detectedAt; }

    public Instant getFirstDetectedAt() { return firstDetectedAt; }
    public void setFirstDetectedAt(Instant firstDetectedAt) { this.firstDetectedAt = firstDetectedAt; }

    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public UUID getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(UUID resolvedBy) { this.resolvedBy = resolvedBy; }

    public String getResolutionNotes() { return resolutionNotes; }
    public void setResolutionNotes(String resolutionNotes) { this.resolutionNotes = resolutionNotes; }

    public UUID getIctIncidentId() { return ictIncidentId; }
    public void setIctIncidentId(UUID ictIncidentId) { this.ictIncidentId = ictIncidentId; }
}
