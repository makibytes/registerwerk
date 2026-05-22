package de.makibytes.registerwerk.asset.api;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "asset_bond_terms")
public class AssetBondTerms {

    @Id
    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "face_value", nullable = false, precision = 38, scale = 18)
    private BigDecimal faceValue;

    @Column(name = "currency_iso", nullable = false, length = 3)
    private String currencyIso;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Column(name = "coupon_rate", precision = 10, scale = 8)
    private BigDecimal couponRate;

    @Column(name = "reference_rate", length = 32)
    private String referenceRate;

    @Column(name = "spread", precision = 10, scale = 8)
    private BigDecimal spread;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_count", nullable = false, length = 20)
    private DayCountConvention dayCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_frequency", nullable = false, length = 16)
    private PaymentFrequency paymentFrequency;

    @Column(nullable = false)
    private boolean callable = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "call_schedule", columnDefinition = "jsonb")
    private List<Map<String, Object>> callSchedule;

    @Enumerated(EnumType.STRING)
    @Column(name = "bond_status", nullable = false, length = 16)
    private BondStatus bondStatus = BondStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public BigDecimal getFaceValue() { return faceValue; }
    public void setFaceValue(BigDecimal faceValue) { this.faceValue = faceValue; }

    public String getCurrencyIso() { return currencyIso; }
    public void setCurrencyIso(String currencyIso) { this.currencyIso = currencyIso; }

    public LocalDate getIssueDate() { return issueDate; }
    public void setIssueDate(LocalDate issueDate) { this.issueDate = issueDate; }

    public LocalDate getMaturityDate() { return maturityDate; }
    public void setMaturityDate(LocalDate maturityDate) { this.maturityDate = maturityDate; }

    public BigDecimal getCouponRate() { return couponRate; }
    public void setCouponRate(BigDecimal couponRate) { this.couponRate = couponRate; }

    public String getReferenceRate() { return referenceRate; }
    public void setReferenceRate(String referenceRate) { this.referenceRate = referenceRate; }

    public BigDecimal getSpread() { return spread; }
    public void setSpread(BigDecimal spread) { this.spread = spread; }

    public DayCountConvention getDayCount() { return dayCount; }
    public void setDayCount(DayCountConvention dayCount) { this.dayCount = dayCount; }

    public PaymentFrequency getPaymentFrequency() { return paymentFrequency; }
    public void setPaymentFrequency(PaymentFrequency paymentFrequency) { this.paymentFrequency = paymentFrequency; }

    public boolean isCallable() { return callable; }
    public void setCallable(boolean callable) { this.callable = callable; }

    public List<Map<String, Object>> getCallSchedule() { return callSchedule; }
    public void setCallSchedule(List<Map<String, Object>> callSchedule) { this.callSchedule = callSchedule; }

    public BondStatus getBondStatus() { return bondStatus; }
    public void setBondStatus(BondStatus bondStatus) { this.bondStatus = bondStatus; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
