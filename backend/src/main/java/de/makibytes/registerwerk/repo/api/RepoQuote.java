package de.makibytes.registerwerk.repo.api;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repo_quote")
public class RepoQuote {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Version
    private long version;
    @Column(name = "rfq_id", nullable = false)
    private UUID rfqId;
    @Column(name = "quoting_entity_id", nullable = false)
    private UUID quotingEntityId;
    @Column(name = "quoting_user_id")
    private UUID quotingUserId;
    @Column(name = "cash_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal cashAmount;
    @Column(name = "repo_rate", nullable = false, precision = 12, scale = 8)
    private BigDecimal repoRate;
    @Column(name = "haircut_bps", nullable = false)
    private int haircutBps;
    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RepoTypes.QuoteStatus status = RepoTypes.QuoteStatus.ACTIVE;
    @Column(length = 500)
    private String message;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist @PreUpdate void touch() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getRfqId() { return rfqId; }
    public void setRfqId(UUID value) { rfqId = value; }
    public UUID getQuotingEntityId() { return quotingEntityId; }
    public void setQuotingEntityId(UUID value) { quotingEntityId = value; }
    public UUID getQuotingUserId() { return quotingUserId; }
    public void setQuotingUserId(UUID value) { quotingUserId = value; }
    public BigDecimal getCashAmount() { return cashAmount; }
    public void setCashAmount(BigDecimal value) { cashAmount = value; }
    public BigDecimal getRepoRate() { return repoRate; }
    public void setRepoRate(BigDecimal value) { repoRate = value; }
    public int getHaircutBps() { return haircutBps; }
    public void setHaircutBps(int value) { haircutBps = value; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant value) { validUntil = value; }
    public RepoTypes.QuoteStatus getStatus() { return status; }
    public void setStatus(RepoTypes.QuoteStatus value) { status = value; }
    public String getMessage() { return message; }
    public void setMessage(String value) { message = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
