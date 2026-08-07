package de.makibytes.registerwerk.asset.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.asset.api.OnchainLevel;
import de.makibytes.registerwerk.deployment.api.EntryType;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "asset")
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Optimistic-lock guard. {@code AssetLifecycleService}'s submit/approve/reject/issue/
     * suspend/reactivate/redeem methods all do a read-modify-write on {@link #status}; without
     * a version check, two concurrent transitions can both pass their precondition check and
     * both commit. With it, the losing write fails and the caller retries (surfaced as HTTP 409).
     */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "asset_number", nullable = false, unique = true, length = 30)
    @NotBlank
    private String assetNumber;

    @Column(name = "issuer_id", nullable = false)
    @NotNull
    private UUID issuerId;

    @Column(nullable = false, length = 500)
    @NotBlank
    private String name;

    @Column(length = 12)
    private String isin;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_standard", nullable = false, length = 30)
    @NotNull
    private TokenStandard tokenStandard;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Chain chain;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Network network;

    @Enumerated(EnumType.STRING)
    @Column(name = "onchain_level", nullable = false, length = 10)
    @NotNull
    private OnchainLevel onchainLevel = OnchainLevel.NONE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull
    private AssetStatus status = AssetStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "jurisdiction", length = 20)
    private Jurisdiction jurisdiction;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private EntryType entryType = EntryType.COLLECTIVE;

    @Column(name = "termsheet_doc_id")
    private UUID termsheetDocId;

    /**
     * ISO-4217 currency code (e.g. "EUR"). Distinct from {@code AssetBondTerms.currencyIso},
     * which only exists for bond-standard assets and is entered separately through the
     * bond-terms flow — this field applies to every token standard, since even a non-bond
     * asset (an ERC-1155 fund share, an ERC-3643 equity token) needs a denomination currency
     * for statements, valuation, and tax reporting to have anything to work from.
     */
    @Column(length = 3)
    private String currency;

    /** Total nominal issuance amount, in {@link #currency}. Null until set by the issuer. */
    @Column(name = "issue_size", precision = 38, scale = 8)
    private BigDecimal issueSize;

    /** Minimum tradable/transferable unit, in {@link #currency}. Null until set by the issuer. */
    @Column(precision = 38, scale = 8)
    private BigDecimal denomination;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    /** Null for perpetual/equity-like instruments with no fixed maturity. */
    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "public_data", columnDefinition = "jsonb")
    private Map<String, Object> publicData;

    @Column(name = "last_holder_sync_time")
    private Instant lastHolderSyncTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public long getVersion() { return version; }

    public String getAssetNumber() { return assetNumber; }
    public void setAssetNumber(String assetNumber) { this.assetNumber = assetNumber; }

    public UUID getIssuerId() { return issuerId; }
    public void setIssuerId(UUID issuerId) { this.issuerId = issuerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIsin() { return isin; }
    public void setIsin(String isin) { this.isin = isin; }

    public TokenStandard getTokenStandard() { return tokenStandard; }
    public void setTokenStandard(TokenStandard tokenStandard) { this.tokenStandard = tokenStandard; }

    public Chain getChain() { return chain; }
    public void setChain(Chain chain) { this.chain = chain; }

    public Network getNetwork() { return network; }
    public void setNetwork(Network network) { this.network = network; }

    public OnchainLevel getOnchainLevel() { return onchainLevel; }
    public void setOnchainLevel(OnchainLevel onchainLevel) { this.onchainLevel = onchainLevel; }

    public AssetStatus getStatus() { return status; }
    public void setStatus(AssetStatus status) { this.status = status; }

    public EntryType getEntryType() { return entryType; }
    public void setEntryType(EntryType entryType) { this.entryType = entryType; }

    public Jurisdiction getJurisdiction() { return jurisdiction; }
    public void setJurisdiction(Jurisdiction jurisdiction) { this.jurisdiction = jurisdiction; }

    public UUID getTermsheetDocId() { return termsheetDocId; }
    public void setTermsheetDocId(UUID termsheetDocId) { this.termsheetDocId = termsheetDocId; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getIssueSize() { return issueSize; }
    public void setIssueSize(BigDecimal issueSize) { this.issueSize = issueSize; }

    public BigDecimal getDenomination() { return denomination; }
    public void setDenomination(BigDecimal denomination) { this.denomination = denomination; }

    public LocalDate getIssueDate() { return issueDate; }
    public void setIssueDate(LocalDate issueDate) { this.issueDate = issueDate; }

    public LocalDate getMaturityDate() { return maturityDate; }
    public void setMaturityDate(LocalDate maturityDate) { this.maturityDate = maturityDate; }

    public Map<String, Object> getPublicData() { return publicData; }
    public void setPublicData(Map<String, Object> publicData) { this.publicData = publicData; }

    public Instant getLastHolderSyncTime() { return lastHolderSyncTime; }
    public void setLastHolderSyncTime(Instant lastHolderSyncTime) { this.lastHolderSyncTime = lastHolderSyncTime; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
