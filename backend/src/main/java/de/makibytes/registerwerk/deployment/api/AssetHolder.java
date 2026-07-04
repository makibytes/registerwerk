package de.makibytes.registerwerk.deployment.api;

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

    /**
     * Optimistic-lock guard. The nominal amount is the legally canonical register
     * balance (eWpG §16) and is mutated by read-modify-write in several concurrent
     * paths — trade settlement, manual corrections, indexer sync. Without a version
     * check, two concurrent updates silently lose one another; with it, the losing
     * write fails and the caller retries (surfaced as HTTP 409).
     */
    @Version
    @Column(nullable = false)
    private long version;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private EntryType entryType = EntryType.COLLECTIVE;

    /** §17(2) eWpG: pseudonymous unique identifier for single-entry holders. */
    @Column(name = "holder_reference", length = 64)
    private String holderReference;

    /** §19(2) eWpG: register statements are owed only toward consumer holders. */
    @Column(name = "is_consumer", nullable = false)
    private Boolean isConsumer = false;

    @Column(name = "third_party_rights")
    private String thirdPartyRights;

    @Column(name = "disposal_restrictions")
    private String disposalRestrictions;

    @Column(name = "legal_capacity_note")
    private String legalCapacityNote;

    @Column(name = "last_statement_at")
    private Instant lastStatementAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public long getVersion() { return version; }

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

    public EntryType getEntryType() { return entryType; }
    public void setEntryType(EntryType entryType) { this.entryType = entryType; }
    public String getHolderReference() { return holderReference; }
    public void setHolderReference(String holderReference) { this.holderReference = holderReference; }
    public Boolean getIsConsumer() { return isConsumer; }
    public void setIsConsumer(Boolean isConsumer) { this.isConsumer = isConsumer; }
    public String getThirdPartyRights() { return thirdPartyRights; }
    public void setThirdPartyRights(String thirdPartyRights) { this.thirdPartyRights = thirdPartyRights; }
    public String getDisposalRestrictions() { return disposalRestrictions; }
    public void setDisposalRestrictions(String disposalRestrictions) { this.disposalRestrictions = disposalRestrictions; }
    public String getLegalCapacityNote() { return legalCapacityNote; }
    public void setLegalCapacityNote(String legalCapacityNote) { this.legalCapacityNote = legalCapacityNote; }
    public Instant getLastStatementAt() { return lastStatementAt; }
    public void setLastStatementAt(Instant lastStatementAt) { this.lastStatementAt = lastStatementAt; }
}
