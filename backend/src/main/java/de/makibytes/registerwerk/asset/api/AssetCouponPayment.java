package de.makibytes.registerwerk.asset.api;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "asset_coupon_payment")
public class AssetCouponPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "slot_id", precision = 78, scale = 0)
    private BigInteger slotId;

    @Column(name = "period_no", nullable = false)
    private int periodNo;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Column(name = "amount_per_unit", precision = 38, scale = 18)
    private BigDecimal amountPerUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "coupon_status", nullable = false, length = 16)
    private CouponStatus couponStatus = CouponStatus.SCHEDULED;

    @Column(name = "tx_ref", length = 120)
    private String txRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public BigInteger getSlotId() { return slotId; }
    public void setSlotId(BigInteger slotId) { this.slotId = slotId; }

    public int getPeriodNo() { return periodNo; }
    public void setPeriodNo(int periodNo) { this.periodNo = periodNo; }

    public LocalDate getScheduledDate() { return scheduledDate; }
    public void setScheduledDate(LocalDate scheduledDate) { this.scheduledDate = scheduledDate; }

    public LocalDate getPaidDate() { return paidDate; }
    public void setPaidDate(LocalDate paidDate) { this.paidDate = paidDate; }

    public BigDecimal getAmountPerUnit() { return amountPerUnit; }
    public void setAmountPerUnit(BigDecimal amountPerUnit) { this.amountPerUnit = amountPerUnit; }

    public CouponStatus getCouponStatus() { return couponStatus; }
    public void setCouponStatus(CouponStatus couponStatus) { this.couponStatus = couponStatus; }

    public String getTxRef() { return txRef; }
    public void setTxRef(String txRef) { this.txRef = txRef; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
