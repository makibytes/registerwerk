package de.makibytes.registerwerk.registertransfer.api;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Investor-side counterpart to {@link RegisterTransfer}: moves ONE investor's ONE holding to a
 * successor/competitor registrar, as part of a customer off-ramp
 * ({@code customer.internal.CustomerOffboardingService.terminate}) or a standalone request.
 * Reuses {@link TransferStatus} — the lifecycle shape is identical (initiate → export the
 * §20-eWpRV-style data package → record the on-chain handover → complete).
 */
@Entity
@Table(name = "portfolio_migration_request")
public class PortfolioMigrationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "investor_entity_id", nullable = false)
    private UUID investorEntityId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "holder_id", nullable = false)
    private UUID holderId;

    @Column(name = "destination_registrar_name")
    private String destinationRegistrarName;

    @Column(name = "destination_registrar_identifier")
    private String destinationRegistrarIdentifier;

    @Column(name = "destination_wallet_address")
    private String destinationWalletAddress;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
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
    public UUID getInvestorEntityId() { return investorEntityId; }
    public void setInvestorEntityId(UUID v) { this.investorEntityId = v; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID v) { this.assetId = v; }
    public UUID getHolderId() { return holderId; }
    public void setHolderId(UUID v) { this.holderId = v; }
    public String getDestinationRegistrarName() { return destinationRegistrarName; }
    public void setDestinationRegistrarName(String v) { this.destinationRegistrarName = v; }
    public String getDestinationRegistrarIdentifier() { return destinationRegistrarIdentifier; }
    public void setDestinationRegistrarIdentifier(String v) { this.destinationRegistrarIdentifier = v; }
    public String getDestinationWalletAddress() { return destinationWalletAddress; }
    public void setDestinationWalletAddress(String v) { this.destinationWalletAddress = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public TransferStatus getStatus() { return status; }
    public void setStatus(TransferStatus v) { this.status = v; }
    public String getExportHash() { return exportHash; }
    public void setExportHash(String v) { this.exportHash = v; }
    public Map<String, Object> getExportManifest() { return exportManifest; }
    public void setExportManifest(Map<String, Object> v) { this.exportManifest = v; }
    public String getOnchainTxHash() { return onchainTxHash; }
    public void setOnchainTxHash(String v) { this.onchainTxHash = v; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(UUID v) { this.initiatedBy = v; }
    public Instant getInitiatedAt() { return initiatedAt; }
    public Instant getExportedAt() { return exportedAt; }
    public void setExportedAt(Instant v) { this.exportedAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
