package de.makibytes.registerwerk.corporateactions.api;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Aggregate root for a corporate action (coupon, dividend, split, redemption, etc.)
 * on a tokenized asset. Settlement dispatches to the appropriate blockchain adapter
 * based on the asset's token standard.
 */
@Entity
@Table(name = "corporate_action")
public class CorporateAction {

    public enum ActionType {
        COUPON, DIVIDEND, SPLIT, REVERSE_SPLIT, CONVERSION,
        REDEMPTION, PARTIAL_REDEMPTION, PLEDGE, CALL,
        CAPITAL_CALL, INTEREST_PAYMENT
    }

    public enum Status {
        ANNOUNCED, RECORD_DATE_SET, COMPUTED, AWAITING_SETTLEMENT, SETTLED, CLOSED, CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30)
    private ActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status = Status.ANNOUNCED;

    @Column(name = "announcement_date")
    private LocalDate announcementDate;

    @Column(name = "record_date")
    private LocalDate recordDate;

    @Column(name = "ex_date")
    private LocalDate exDate;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "ratio_numerator", precision = 38, scale = 18)
    private BigDecimal ratioNumerator;

    @Column(name = "ratio_denominator", precision = 38, scale = 18)
    private BigDecimal ratioDenominator;

    @Column(name = "amount_per_unit", precision = 38, scale = 18)
    private BigDecimal amountPerUnit;

    @Column(name = "total_amount", precision = 38, scale = 18)
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "coupon_payment_id")
    private UUID couponPaymentId;

    @Column(name = "bond_period_start")
    private LocalDate bondPeriodStart;

    @Column(name = "bond_period_end")
    private LocalDate bondPeriodEnd;

    @Column(name = "settlement_tx_hash")
    private String settlementTxHash;

    @Column(name = "settlement_chain")
    private String settlementChain;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "initiated_by", nullable = false)
    private UUID initiatedBy;

    @Column(name = "dual_control_approver_id")
    private UUID dualControlApproverId;

    @Column(name = "dual_control_approved_at")
    private Instant dualControlApprovedAt;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID v) { this.assetId = v; }
    public ActionType getActionType() { return actionType; }
    public void setActionType(ActionType v) { this.actionType = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; updatedAt = Instant.now(); }
    public LocalDate getAnnouncementDate() { return announcementDate; }
    public void setAnnouncementDate(LocalDate v) { this.announcementDate = v; }
    public LocalDate getRecordDate() { return recordDate; }
    public void setRecordDate(LocalDate v) { this.recordDate = v; }
    public LocalDate getExDate() { return exDate; }
    public void setExDate(LocalDate v) { this.exDate = v; }
    public LocalDate getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDate v) { this.paymentDate = v; }
    public BigDecimal getRatioNumerator() { return ratioNumerator; }
    public void setRatioNumerator(BigDecimal v) { this.ratioNumerator = v; }
    public BigDecimal getRatioDenominator() { return ratioDenominator; }
    public void setRatioDenominator(BigDecimal v) { this.ratioDenominator = v; }
    public BigDecimal getAmountPerUnit() { return amountPerUnit; }
    public void setAmountPerUnit(BigDecimal v) { this.amountPerUnit = v; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal v) { this.totalAmount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public UUID getCouponPaymentId() { return couponPaymentId; }
    public void setCouponPaymentId(UUID v) { this.couponPaymentId = v; }
    public LocalDate getBondPeriodStart() { return bondPeriodStart; }
    public void setBondPeriodStart(LocalDate v) { this.bondPeriodStart = v; }
    public LocalDate getBondPeriodEnd() { return bondPeriodEnd; }
    public void setBondPeriodEnd(LocalDate v) { this.bondPeriodEnd = v; }
    public String getSettlementTxHash() { return settlementTxHash; }
    public void setSettlementTxHash(String v) { this.settlementTxHash = v; }
    public String getSettlementChain() { return settlementChain; }
    public void setSettlementChain(String v) { this.settlementChain = v; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant v) { this.settledAt = v; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(UUID v) { this.initiatedBy = v; }
    public UUID getDualControlApproverId() { return dualControlApproverId; }
    public void setDualControlApproverId(UUID v) { this.dualControlApproverId = v; }
    public Instant getDualControlApprovedAt() { return dualControlApprovedAt; }
    public void setDualControlApprovedAt(Instant v) { this.dualControlApprovedAt = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
