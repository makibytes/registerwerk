package de.makibytes.registerwerk.registertransfer.api;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A §§21/22 eWpG register transfer to a successor registry operator.
 *
 * <p>Records the off-chain half of a registry handover: the successor, the
 * reason (§22 allows a forced handover where the operator can no longer meet
 * the statutory requirements), the exported data package and its hash (the §20
 * eWpRV data transfer), and a link to the on-chain control handover transaction.
 */
@Entity
@Table(name = "register_transfer")
public class RegisterTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "successor_name", nullable = false)
    private String successorName;

    @Column(name = "successor_identifier")
    private String successorIdentifier;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferStatus status = TransferStatus.INITIATED;

    @Column(name = "export_hash", length = 66)
    private String exportHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "export_manifest")
    private Map<String, Object> exportManifest;

    @Column(name = "onchain_tx_hash", length = 66)
    private String onchainTxHash;

    @Column(name = "initiated_by")
    private UUID initiatedBy;

    @Column(name = "initiated_at", nullable = false, updatable = false)
    private Instant initiatedAt = Instant.now();

    @Column(name = "exported_at")
    private Instant exportedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }
    public String getSuccessorName() { return successorName; }
    public void setSuccessorName(String successorName) { this.successorName = successorName; }
    public String getSuccessorIdentifier() { return successorIdentifier; }
    public void setSuccessorIdentifier(String successorIdentifier) { this.successorIdentifier = successorIdentifier; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public TransferStatus getStatus() { return status; }
    public void setStatus(TransferStatus status) { this.status = status; }
    public String getExportHash() { return exportHash; }
    public void setExportHash(String exportHash) { this.exportHash = exportHash; }
    public Map<String, Object> getExportManifest() { return exportManifest; }
    public void setExportManifest(Map<String, Object> exportManifest) { this.exportManifest = exportManifest; }
    public String getOnchainTxHash() { return onchainTxHash; }
    public void setOnchainTxHash(String onchainTxHash) { this.onchainTxHash = onchainTxHash; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(UUID initiatedBy) { this.initiatedBy = initiatedBy; }
    public Instant getInitiatedAt() { return initiatedAt; }
    public Instant getExportedAt() { return exportedAt; }
    public void setExportedAt(Instant exportedAt) { this.exportedAt = exportedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
