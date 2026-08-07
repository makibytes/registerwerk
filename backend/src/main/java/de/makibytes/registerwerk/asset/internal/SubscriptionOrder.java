package de.makibytes.registerwerk.asset.internal;

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
 * A primary-market subscription order: an investor requests an amount of a not-yet-fully-placed
 * issuance, the issuer/operator allocates (fully or partially, "scaling" an oversubscribed
 * issuance), and the investor confirms before the position is actually entered on the register.
 *
 * <p>Previously the only way to create a position was an issuer manually typing a wallet
 * address and nominal amount into a dialog — no order, no allocation, no investor confirmation,
 * so a bank had no way to distribute a new issue through the portal.
 */
@Entity
@Table(name = "subscription_order")
public class SubscriptionOrder {

    public enum Status { SUBMITTED, ALLOCATED, CONFIRMED, REJECTED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    /** The investor's legal entity — who the resulting {@code AssetHolder} will belong to. */
    @Column(name = "investor_entity_id", nullable = false)
    private UUID investorEntityId;

    @Column(name = "wallet_address", nullable = false)
    private String walletAddress;

    @Column(name = "requested_amount", nullable = false)
    private BigDecimal requestedAmount;

    /** Null until {@link Status#ALLOCATED} — may be less than {@link #requestedAmount} (scaling). */
    @Column(name = "allocated_amount")
    private BigDecimal allocatedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.SUBMITTED;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "allocated_at")
    private Instant allocatedAt;

    @Column(name = "allocated_by")
    private UUID allocatedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** Set once {@link Status#CONFIRMED} creates the actual register position. */
    @Column(name = "resulting_holder_id")
    private UUID resultingHolderId;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    public UUID getId() { return id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public UUID getInvestorEntityId() { return investorEntityId; }
    public void setInvestorEntityId(UUID investorEntityId) { this.investorEntityId = investorEntityId; }

    public String getWalletAddress() { return walletAddress; }
    public void setWalletAddress(String walletAddress) { this.walletAddress = walletAddress; }

    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public void setRequestedAmount(BigDecimal requestedAmount) { this.requestedAmount = requestedAmount; }

    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
    public void setAllocatedAmount(BigDecimal allocatedAmount) { this.allocatedAmount = allocatedAmount; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Instant getSubmittedAt() { return submittedAt; }

    public Instant getAllocatedAt() { return allocatedAt; }
    public void setAllocatedAt(Instant allocatedAt) { this.allocatedAt = allocatedAt; }

    public UUID getAllocatedBy() { return allocatedBy; }
    public void setAllocatedBy(UUID allocatedBy) { this.allocatedBy = allocatedBy; }

    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }

    public UUID getResultingHolderId() { return resultingHolderId; }
    public void setResultingHolderId(UUID resultingHolderId) { this.resultingHolderId = resultingHolderId; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
}
