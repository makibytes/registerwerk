package de.makibytes.registerwerk.asset.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A primary-market subscription order: an investor requests an amount of a not-yet-fully-placed
 * issuance, the issuer/operator allocates (fully or partially, "scaling" an oversubscribed
 * issuance), and the investor confirms before the position is actually entered on the register.
 *
 * <p>T3-08 flow: SUBMITTED -> ALLOCATED -> (investor accepts) -> PAYMENT_CONFIRMED (issuer/operator, after
 * the cash arrived) -> SETTLED (compliance gates re-run, then mint or register credit). Exits:
 * REJECTED, CANCELLED (before allocation), LAPSED (no payment within the window), RELEASED
 * (issuer/operator gave the allocation back). CONFIRMED is the legacy state of the old flow, in which
 * the investor's confirm entered the register with no payment; it is read-only now.
 *
 * <p>Previously the only way to create a position was an issuer manually typing a wallet
 * address and nominal amount into a dialog — no order, no allocation, no investor confirmation,
 * so a bank had no way to distribute a new issue through the portal.
 */
@Entity
@Table(name = "subscription_order")
public class SubscriptionOrder {

    public enum Status {
        SUBMITTED, ALLOCATED, PAYMENT_CONFIRMED, SETTLED, CONFIRMED, REJECTED, CANCELLED, LAPSED, RELEASED;

        /** States whose allocated amount is still spoken for (counts against issue size). */
        public static final java.util.Set<Status> CAPACITY_HOLDING =
                java.util.EnumSet.of(ALLOCATED, PAYMENT_CONFIRMED, SETTLED, CONFIRMED);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    @Column(nullable = false)
    private long version;

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

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    /** Payment deadline of an ALLOCATED order; the lapse job moves it to LAPSED after this. */
    @Column(name = "allocation_expires_at")
    private Instant allocationExpiresAt;

    @Column(name = "amount_due")
    private BigDecimal amountDue;

    @Column(name = "payment_currency", length = 10)
    private String paymentCurrency;

    @Column(name = "paid_amount")
    private BigDecimal paidAmount;

    /** Overpayment (paid - due) or the full payment of a released paid order; to be refunded. */
    @Column(name = "refund_due")
    private BigDecimal refundDue;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "payment_value_date")
    private java.time.LocalDate paymentValueDate;

    @Column(name = "payment_confirmed_at")
    private Instant paymentConfirmedAt;

    @Column(name = "payment_confirmed_by")
    private UUID paymentConfirmedBy;

    @Column(name = "settled_at")
    private Instant settledAt;

    /** Blockchain-transaction tracking id of the mint, when the asset is deployed. */
    @Column(name = "settlement_tx_id")
    private UUID settlementTxId;

    @Column(name = "lapsed_at")
    private Instant lapsedAt;

    @Column(name = "release_reason")
    private String releaseReason;

    public UUID getId() { return id; }

    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }
    public Instant getAllocationExpiresAt() { return allocationExpiresAt; }
    public void setAllocationExpiresAt(Instant allocationExpiresAt) { this.allocationExpiresAt = allocationExpiresAt; }
    public BigDecimal getAmountDue() { return amountDue; }
    public void setAmountDue(BigDecimal amountDue) { this.amountDue = amountDue; }
    public String getPaymentCurrency() { return paymentCurrency; }
    public void setPaymentCurrency(String paymentCurrency) { this.paymentCurrency = paymentCurrency; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    public BigDecimal getRefundDue() { return refundDue; }
    public void setRefundDue(BigDecimal refundDue) { this.refundDue = refundDue; }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }
    public java.time.LocalDate getPaymentValueDate() { return paymentValueDate; }
    public void setPaymentValueDate(java.time.LocalDate paymentValueDate) { this.paymentValueDate = paymentValueDate; }
    public Instant getPaymentConfirmedAt() { return paymentConfirmedAt; }
    public void setPaymentConfirmedAt(Instant paymentConfirmedAt) { this.paymentConfirmedAt = paymentConfirmedAt; }
    public UUID getPaymentConfirmedBy() { return paymentConfirmedBy; }
    public void setPaymentConfirmedBy(UUID paymentConfirmedBy) { this.paymentConfirmedBy = paymentConfirmedBy; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    public UUID getSettlementTxId() { return settlementTxId; }
    public void setSettlementTxId(UUID settlementTxId) { this.settlementTxId = settlementTxId; }
    public Instant getLapsedAt() { return lapsedAt; }
    public void setLapsedAt(Instant lapsedAt) { this.lapsedAt = lapsedAt; }
    public String getReleaseReason() { return releaseReason; }
    public void setReleaseReason(String releaseReason) { this.releaseReason = releaseReason; }

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
