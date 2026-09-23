package de.makibytes.registerwerk.deployment.api;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vault_request")
public class VaultRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "request_id", nullable = false, precision = 78, scale = 0)
    private BigInteger requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 8)
    private VaultRequestType requestType;

    @Column(name = "controller_addr", nullable = false, length = 80)
    private String controllerAddr;

    @Column(name = "owner_addr", nullable = false, length = 80)
    private String ownerAddr;

    @Column(name = "asset_amount", precision = 78, scale = 0)
    private BigInteger assetAmount;

    @Column(name = "share_amount", precision = 78, scale = 0)
    private BigInteger shareAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_status", nullable = false, length = 16)
    private VaultRequestStatus requestStatus = VaultRequestStatus.PENDING;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "fulfilled_tx", length = 80)
    private String fulfilledTx;

    @Column(name = "nav_at_fulfill", precision = 38, scale = 18)
    private BigDecimal navAtFulfill;

    /** Set when a {@code cancelDepositRequest}/{@code cancelRedeemRequest} tx is submitted;
     *  mirrors {@link #fulfilledTx} for the cancel path. The two are mutually exclusive per row —
     *  a request can only ever be fulfilled or cancelled once. */
    @Column(name = "cancelled_tx", length = 80)
    private String cancelledTx;

    /** FK to {@code chain_config} — set only once {@link #confirmed} is true. */
    @Column(name = "chain_config_id")
    private UUID chainConfigId;

    @Column(name = "block_number")
    private Long blockNumber;

    /** Exact block occurrence which produced the current resolution. */
    @Column(name = "block_hash", length = 128)
    private String blockHash;

    /** True once {@link VaultConfirmationListener} has confirmed whichever tx ({@link
     *  #fulfilledTx} or {@link #cancelledTx}) was submitted and applied the corresponding status
     *  transition. {@link #requestStatus} deliberately stays {@code PENDING} until then — see
     *  that field's usage in {@code Erc7540AdminService} for why. */
    @Column(name = "confirmed", nullable = false)
    private boolean confirmed = false;

    /** Who funded a deposit request ({@code depositRequestPayer}); cancel refunds go here. Null
     *  for redeem requests and for vaults deployed before the payer was recorded on-chain. */
    @Column(name = "payer_addr", length = 80)
    private String payerAddr;

    /** Exact occurrence of the {@code DepositRequested}/{@code RedeemRequested} log this row was
     *  ingested from — see {@code VaultRequestIngestionService}. */
    @Column(name = "requested_tx", length = 80)
    private String requestedTx;

    @Column(name = "requested_block_number")
    private Long requestedBlockNumber;

    @Column(name = "requested_block_hash", length = 128)
    private String requestedBlockHash;

    /** Escrow destination of a registry force-cancel ({@link VaultRequestStatus#FORCE_CANCELLED}).
     *  Set together with {@link #cancelledTx} when the force-cancel is submitted. */
    @Column(name = "forced_to_addr", length = 80)
    private String forcedToAddr;

    @Column(name = "legal_basis", length = 1000)
    private String legalBasis;

    /** Non-null when a confirmed resolution could not be reconciled with its on-chain event
     *  (e.g. a fulfil receipt without a matching {@code *Fulfilled} log) and needs a human. */
    @Column(name = "review_note", length = 500)
    private String reviewNote;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public BigInteger getRequestId() { return requestId; }
    public void setRequestId(BigInteger requestId) { this.requestId = requestId; }

    public VaultRequestType getRequestType() { return requestType; }
    public void setRequestType(VaultRequestType requestType) { this.requestType = requestType; }

    public String getControllerAddr() { return controllerAddr; }
    public void setControllerAddr(String controllerAddr) { this.controllerAddr = controllerAddr; }

    public String getOwnerAddr() { return ownerAddr; }
    public void setOwnerAddr(String ownerAddr) { this.ownerAddr = ownerAddr; }

    public BigInteger getAssetAmount() { return assetAmount; }
    public void setAssetAmount(BigInteger assetAmount) { this.assetAmount = assetAmount; }

    public BigInteger getShareAmount() { return shareAmount; }
    public void setShareAmount(BigInteger shareAmount) { this.shareAmount = shareAmount; }

    public VaultRequestStatus getRequestStatus() { return requestStatus; }
    public void setRequestStatus(VaultRequestStatus requestStatus) { this.requestStatus = requestStatus; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public Instant getFulfilledAt() { return fulfilledAt; }
    public void setFulfilledAt(Instant fulfilledAt) { this.fulfilledAt = fulfilledAt; }

    public String getFulfilledTx() { return fulfilledTx; }
    public void setFulfilledTx(String fulfilledTx) { this.fulfilledTx = fulfilledTx; }

    public BigDecimal getNavAtFulfill() { return navAtFulfill; }
    public void setNavAtFulfill(BigDecimal navAtFulfill) { this.navAtFulfill = navAtFulfill; }

    public String getCancelledTx() { return cancelledTx; }
    public void setCancelledTx(String cancelledTx) { this.cancelledTx = cancelledTx; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public Long getBlockNumber() { return blockNumber; }
    public void setBlockNumber(Long blockNumber) { this.blockNumber = blockNumber; }

    public String getBlockHash() { return blockHash; }
    public void setBlockHash(String blockHash) { this.blockHash = blockHash; }

    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }

    public String getPayerAddr() { return payerAddr; }
    public void setPayerAddr(String payerAddr) { this.payerAddr = payerAddr; }

    public String getRequestedTx() { return requestedTx; }
    public void setRequestedTx(String requestedTx) { this.requestedTx = requestedTx; }

    public Long getRequestedBlockNumber() { return requestedBlockNumber; }
    public void setRequestedBlockNumber(Long requestedBlockNumber) { this.requestedBlockNumber = requestedBlockNumber; }

    public String getRequestedBlockHash() { return requestedBlockHash; }
    public void setRequestedBlockHash(String requestedBlockHash) { this.requestedBlockHash = requestedBlockHash; }

    public String getForcedToAddr() { return forcedToAddr; }
    public void setForcedToAddr(String forcedToAddr) { this.forcedToAddr = forcedToAddr; }

    public String getLegalBasis() { return legalBasis; }
    public void setLegalBasis(String legalBasis) { this.legalBasis = legalBasis; }

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
