package de.makibytes.registerwerk.repo.api;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A borrower's request to swap the collateral of an open repo; approval starts a two-leg settlement. */
@Entity
@Table(name = "repo_substitution_request")
public class RepoSubstitutionRequest {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Version private long version;
    @Column(name = "repo_trade_id", nullable = false, updatable = false) private UUID repoTradeId;
    @Column(name = "asset_id", nullable = false, updatable = false) private UUID assetId;
    @Column(nullable = false, updatable = false, precision = 96, scale = 18) private BigDecimal quantity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private RepoTypes.SubstitutionStatus status = RepoTypes.SubstitutionStatus.PENDING;
    @Column(name = "requested_by", nullable = false, updatable = false) private UUID requestedBy;
    @Column(name = "requested_at", nullable = false, updatable = false) private Instant requestedAt = Instant.now();
    @Column(name = "decided_by") private UUID decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "replacement_received_at") private Instant replacementReceivedAt;
    @Column(name = "original_returned_at") private Instant originalReturnedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(length = 1000) private String note;

    public UUID getId() { return id; }
    public UUID getRepoTradeId() { return repoTradeId; } public void setRepoTradeId(UUID v) { repoTradeId = v; }
    public UUID getAssetId() { return assetId; } public void setAssetId(UUID v) { assetId = v; }
    public BigDecimal getQuantity() { return quantity; } public void setQuantity(BigDecimal v) { quantity = v; }
    public RepoTypes.SubstitutionStatus getStatus() { return status; } public void setStatus(RepoTypes.SubstitutionStatus v) { status = v; }
    public UUID getRequestedBy() { return requestedBy; } public void setRequestedBy(UUID v) { requestedBy = v; }
    public Instant getRequestedAt() { return requestedAt; }
    public UUID getDecidedBy() { return decidedBy; } public void setDecidedBy(UUID v) { decidedBy = v; }
    public Instant getDecidedAt() { return decidedAt; } public void setDecidedAt(Instant v) { decidedAt = v; }
    public Instant getReplacementReceivedAt() { return replacementReceivedAt; } public void setReplacementReceivedAt(Instant v) { replacementReceivedAt = v; }
    public Instant getOriginalReturnedAt() { return originalReturnedAt; } public void setOriginalReturnedAt(Instant v) { originalReturnedAt = v; }
    public Instant getCompletedAt() { return completedAt; } public void setCompletedAt(Instant v) { completedAt = v; }
    public String getNote() { return note; } public void setNote(String v) { note = v; }
}
