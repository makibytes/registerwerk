package de.makibytes.registerwerk.registerstatement.api;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An issued register statement (Registerauszug, §19 eWpG). Each statement is
 * itself a register record: it captures the register content shown to the
 * holder at issuance time, the hash of the rendered text-form document, and the
 * delivery outcome, so the operator can later prove what was disclosed and when.
 */
@Entity
@Table(name = "register_statement")
public class RegisterStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "holder_id", nullable = false)
    private UUID holderId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "investor_id", nullable = false)
    private UUID investorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, length = 20)
    private StatementTrigger trigger;

    @Column(name = "nominal_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal nominalAmount;

    @Column(name = "wallet_address", length = 66)
    private String walletAddress;

    @Column(name = "holder_reference", length = 64)
    private String holderReference;

    @Column(name = "content_hash", nullable = false, length = 66)
    private String contentHash;

    /**
     * Reference into a document archive, once one is available to this module.
     * Currently null: the statement PDF is delivered by e-mail and pinned by its
     * {@link #contentHash} (tamper-evident), but not yet archived as a blob. The
     * regreporting S3 store is module-private and report-specific, so wiring a
     * shared document port is deferred rather than breaching the module boundary.
     */
    @Column(name = "pdf_document_id")
    private UUID pdfDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 20)
    private DeliveryStatus deliveryStatus = DeliveryStatus.PENDING;

    @Column(name = "delivery_channel", length = 20)
    private String deliveryChannel;

    @Column(name = "delivery_error")
    private String deliveryError;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    public UUID getId() { return id; }
    public UUID getHolderId() { return holderId; }
    public void setHolderId(UUID holderId) { this.holderId = holderId; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }
    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }
    public StatementTrigger getTrigger() { return trigger; }
    public void setTrigger(StatementTrigger trigger) { this.trigger = trigger; }
    public BigDecimal getNominalAmount() { return nominalAmount; }
    public void setNominalAmount(BigDecimal nominalAmount) { this.nominalAmount = nominalAmount; }
    public String getWalletAddress() { return walletAddress; }
    public void setWalletAddress(String walletAddress) { this.walletAddress = walletAddress; }
    public String getHolderReference() { return holderReference; }
    public void setHolderReference(String holderReference) { this.holderReference = holderReference; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public UUID getPdfDocumentId() { return pdfDocumentId; }
    public void setPdfDocumentId(UUID pdfDocumentId) { this.pdfDocumentId = pdfDocumentId; }
    public DeliveryStatus getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(DeliveryStatus deliveryStatus) { this.deliveryStatus = deliveryStatus; }
    public String getDeliveryChannel() { return deliveryChannel; }
    public void setDeliveryChannel(String deliveryChannel) { this.deliveryChannel = deliveryChannel; }
    public String getDeliveryError() { return deliveryError; }
    public void setDeliveryError(String deliveryError) { this.deliveryError = deliveryError; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
}
