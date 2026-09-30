package de.makibytes.registerwerk.corporateactions.api;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-holder entitlement snapshot for a {@link CorporateAction}.
 *
 * <p>Lifecycle: a row is created for every current holder of the asset when the corporate
 * action reaches {@code RECORD_DATE_SET} ({@code nominalAtRecord} snapshotted then — the
 * entitlement is fixed at record date, not settlement date, per standard securities practice).
 * {@code entitlementAmount} is filled in when the action reaches {@code COMPUTED}.
 * {@code settlementTxHash}/{@code settledAt} are filled in when the action is settled.
 *
 * <p>{@code payoutStatus} (T2-18): an entry snapshotted for a nominee-pool holder (a pool contract
 * holding units on behalf of others) is {@link PayoutStatus#HELD_LOOK_THROUGH} — recorded, but
 * excluded from the action's payable total and from settlement until the look-through question
 * (PARK-T2-18) is decided.
 */
@Entity
@Table(name = "corporate_action_entry")
public class CorporateActionEntry {

    /** Whether an entry's entitlement is paid out at settlement (T2-18). */
    public enum PayoutStatus { PAYABLE, HELD_LOOK_THROUGH }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "corporate_action_id", nullable = false)
    private UUID corporateActionId;

    @Column(name = "asset_holder_id", nullable = false)
    private UUID assetHolderId;

    /** Denormalized from AssetHolder.investorId at snapshot time — lets the Steuerbescheinigung
     *  query "this investor's total income for tax year N" without a cross-module join. */
    @Column(name = "investor_id")
    private UUID investorId;

    @Column(name = "wallet_address", nullable = false)
    private String walletAddress;

    @Column(name = "nominal_at_record", nullable = false, precision = 96, scale = 18)
    private BigDecimal nominalAtRecord;

    @Column(name = "entitlement_amount", precision = 96, scale = 18)
    private BigDecimal entitlementAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_status", nullable = false, length = 24)
    private PayoutStatus payoutStatus = PayoutStatus.PAYABLE;

    @Column(name = "settlement_tx_hash")
    private String settlementTxHash;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getCorporateActionId() { return corporateActionId; }
    public void setCorporateActionId(UUID v) { this.corporateActionId = v; }
    public UUID getAssetHolderId() { return assetHolderId; }
    public void setAssetHolderId(UUID v) { this.assetHolderId = v; }
    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID v) { this.investorId = v; }
    public String getWalletAddress() { return walletAddress; }
    public void setWalletAddress(String v) { this.walletAddress = v; }
    public BigDecimal getNominalAtRecord() { return nominalAtRecord; }
    public void setNominalAtRecord(BigDecimal v) { this.nominalAtRecord = v; }
    public BigDecimal getEntitlementAmount() { return entitlementAmount; }
    public void setEntitlementAmount(BigDecimal v) { this.entitlementAmount = v; }
    public PayoutStatus getPayoutStatus() { return payoutStatus; }
    public void setPayoutStatus(PayoutStatus v) { this.payoutStatus = v; }
    public String getSettlementTxHash() { return settlementTxHash; }
    public void setSettlementTxHash(String v) { this.settlementTxHash = v; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant v) { this.settledAt = v; }
    public Instant getCreatedAt() { return createdAt; }
}
