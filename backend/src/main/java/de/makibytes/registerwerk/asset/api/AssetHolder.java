package de.makibytes.registerwerk.asset.api;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "asset_holder")
public class AssetHolder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "investor_id", nullable = false)
    private UUID investorId;

    @Column(name = "wallet_address", nullable = false, length = 66)
    private String walletAddress;

    @Column(nullable = false)
    private Boolean whitelisted = false;

    @Column(name = "whitelist_tx_hash", length = 66)
    private String whitelistTxHash;

    @Column(name = "nominal_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal nominalAmount = BigDecimal.ZERO;

    @Column(name = "acquisition_date")
    private LocalDate acquisitionDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }

    public String getWalletAddress() { return walletAddress; }
    public void setWalletAddress(String walletAddress) { this.walletAddress = walletAddress; }

    public Boolean getWhitelisted() { return whitelisted; }
    public void setWhitelisted(Boolean whitelisted) { this.whitelisted = whitelisted; }

    public String getWhitelistTxHash() { return whitelistTxHash; }
    public void setWhitelistTxHash(String whitelistTxHash) { this.whitelistTxHash = whitelistTxHash; }

    public BigDecimal getNominalAmount() { return nominalAmount; }
    public void setNominalAmount(BigDecimal nominalAmount) { this.nominalAmount = nominalAmount; }

    public LocalDate getAcquisitionDate() { return acquisitionDate; }
    public void setAcquisitionDate(LocalDate acquisitionDate) { this.acquisitionDate = acquisitionDate; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
