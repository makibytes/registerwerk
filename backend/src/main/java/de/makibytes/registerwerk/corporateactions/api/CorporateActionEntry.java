package de.makibytes.registerwerk.corporateactions.api;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-holder line item of a {@link CorporateAction} — the entitlement snapshot the
 * {@code corporate_action_entry} table was always designed to hold (see
 * {@code V1__initial_schema.sql}), but which had no JPA entity or write path until now: every
 * coupon/dividend action only ever carried an asset-level {@code totalAmount}, with no
 * per-holder breakdown to compute Steuerbescheinigung income or a payment confirmation from.
 *
 * <p>Lifecycle: a row is created for every current holder of the asset when the corporate
 * action reaches {@code RECORD_DATE_SET} ({@code nominalAtRecord} snapshotted then — the
 * entitlement is fixed at record date, not settlement date, per standard securities practice).
 * {@code entitlementAmount} is filled in when the action reaches {@code COMPUTED}.
 * {@code settlementTxHash}/{@code settledAt} are filled in when the action is settled.
 */
@Entity
@Table(name = "corporate_action_entry")
public class CorporateActionEntry {

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

    @Column(name = "nominal_at_record", nullable = false, precision = 38, scale = 18)
    private BigDecimal nominalAtRecord;

    @Column(name = "entitlement_amount", precision = 38, scale = 18)
    private BigDecimal entitlementAmount;

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
    public String getSettlementTxHash() { return settlementTxHash; }
    public void setSettlementTxHash(String v) { this.settlementTxHash = v; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant v) { this.settledAt = v; }
    public Instant getCreatedAt() { return createdAt; }
}
