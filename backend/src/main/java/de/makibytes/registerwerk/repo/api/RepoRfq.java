package de.makibytes.registerwerk.repo.api;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "repo_rfq")
public class RepoRfq {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Version
    private long version;
    @Column(name = "requester_entity_id", nullable = false)
    private UUID requesterEntityId;
    @Column(name = "requester_user_id")
    private UUID requesterUserId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RepoTypes.Side side;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RepoTypes.Visibility visibility;
    @Column(name = "collateral_asset_id", nullable = false)
    private UUID collateralAssetId;
    @Column(name = "collateral_quantity", nullable = false, precision = 38, scale = 18)
    private BigDecimal collateralQuantity;
    @Column(name = "cash_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal cashAmount;
    @Column(name = "cash_currency", nullable = false, length = 3)
    private String cashCurrency;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;
    @Column(name = "proposed_repo_rate", precision = 12, scale = 8)
    private BigDecimal proposedRepoRate;
    @Column(name = "proposed_haircut_bps")
    private Integer proposedHaircutBps;
    @Enumerated(EnumType.STRING) @Column(name = "settlement_method", nullable = false, length = 20)
    private RepoTypes.SettlementMethod settlementMethod = RepoTypes.SettlementMethod.DVP;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RepoTypes.RfqStatus status = RepoTypes.RfqStatus.OPEN;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(length = 1000)
    private String notes;
    @ElementCollection
    @CollectionTable(name = "repo_rfq_target", joinColumns = @JoinColumn(name = "repo_rfq_id"))
    @Column(name = "target_entity_id", nullable = false)
    private Set<UUID> targetEntityIds = new LinkedHashSet<>();
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist @PreUpdate void touch() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public long getVersion() { return version; }
    public UUID getRequesterEntityId() { return requesterEntityId; }
    public void setRequesterEntityId(UUID value) { requesterEntityId = value; }
    public UUID getRequesterUserId() { return requesterUserId; }
    public void setRequesterUserId(UUID value) { requesterUserId = value; }
    public RepoTypes.Side getSide() { return side; }
    public void setSide(RepoTypes.Side value) { side = value; }
    public RepoTypes.Visibility getVisibility() { return visibility; }
    public void setVisibility(RepoTypes.Visibility value) { visibility = value; }
    public UUID getCollateralAssetId() { return collateralAssetId; }
    public void setCollateralAssetId(UUID value) { collateralAssetId = value; }
    public BigDecimal getCollateralQuantity() { return collateralQuantity; }
    public void setCollateralQuantity(BigDecimal value) { collateralQuantity = value; }
    public BigDecimal getCashAmount() { return cashAmount; }
    public void setCashAmount(BigDecimal value) { cashAmount = value; }
    public String getCashCurrency() { return cashCurrency; }
    public void setCashCurrency(String value) { cashCurrency = value; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate value) { startDate = value; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate value) { endDate = value; }
    public BigDecimal getProposedRepoRate() { return proposedRepoRate; }
    public void setProposedRepoRate(BigDecimal value) { proposedRepoRate = value; }
    public Integer getProposedHaircutBps() { return proposedHaircutBps; }
    public void setProposedHaircutBps(Integer value) { proposedHaircutBps = value; }
    public RepoTypes.SettlementMethod getSettlementMethod() { return settlementMethod; }
    public void setSettlementMethod(RepoTypes.SettlementMethod value) { settlementMethod = value; }
    public RepoTypes.RfqStatus getStatus() { return status; }
    public void setStatus(RepoTypes.RfqStatus value) { status = value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt = value; }
    public String getNotes() { return notes; }
    public void setNotes(String value) { notes = value; }
    public Set<UUID> getTargetEntityIds() { return targetEntityIds; }
    public void setTargetEntityIds(Set<UUID> value) { targetEntityIds = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
