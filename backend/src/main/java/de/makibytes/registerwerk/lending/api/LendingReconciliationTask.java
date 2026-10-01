package de.makibytes.registerwerk.lending.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/**
 * Operator work item opened when collateral has left a lending market outside its own
 * repay/liquidate paths (forced transfer/burn, detected by name or by the balance guard). Until
 * an operator attributes the outflow to a borrower with {@code reconcileCollateral} the market
 * is treated as paused for new borrowing (parked decision T5-12). At most one unresolved task
 * exists per market.
 */
@Entity
@Table(name = "lending_reconciliation_task")
public class LendingReconciliationTask {

    public enum Status { OPEN, SUBMITTED, RESOLVED }

    public enum Source { BALANCE_GUARD, FORCED_TRANSFER_EVENT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "market_id", nullable = false)
    private UUID marketId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Source source;

    @Column(nullable = false, precision = 78, scale = 0)
    private BigInteger shortfall = BigInteger.ZERO;

    @Column(name = "token_admin_method", length = 60)
    private String tokenAdminMethod;

    @Column(length = 1000)
    private String detail;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "reconcile_tx_hash", length = 66)
    private String reconcileTxHash;

    @Column(name = "borrower_wallet", length = 66)
    private String borrowerWallet;

    @Column(name = "attributed_amount", precision = 78, scale = 0)
    private BigInteger attributedAmount;

    @Column(name = "forced_transfer_ref", length = 66)
    private String forcedTransferRef;

    @Column(name = "legal_basis", length = 500)
    private String legalBasis;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getMarketId() { return marketId; }
    public void setMarketId(UUID marketId) { this.marketId = marketId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public BigInteger getShortfall() { return shortfall; }
    public void setShortfall(BigInteger shortfall) { this.shortfall = shortfall; }
    public String getTokenAdminMethod() { return tokenAdminMethod; }
    public void setTokenAdminMethod(String tokenAdminMethod) { this.tokenAdminMethod = tokenAdminMethod; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getDetectedAt() { return detectedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public UUID getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(UUID resolvedBy) { this.resolvedBy = resolvedBy; }
    public String getReconcileTxHash() { return reconcileTxHash; }
    public void setReconcileTxHash(String reconcileTxHash) { this.reconcileTxHash = reconcileTxHash; }
    public String getBorrowerWallet() { return borrowerWallet; }
    public void setBorrowerWallet(String borrowerWallet) { this.borrowerWallet = borrowerWallet; }
    public BigInteger getAttributedAmount() { return attributedAmount; }
    public void setAttributedAmount(BigInteger attributedAmount) { this.attributedAmount = attributedAmount; }
    public String getForcedTransferRef() { return forcedTransferRef; }
    public void setForcedTransferRef(String forcedTransferRef) { this.forcedTransferRef = forcedTransferRef; }
    public String getLegalBasis() { return legalBasis; }
    public void setLegalBasis(String legalBasis) { this.legalBasis = legalBasis; }
}
