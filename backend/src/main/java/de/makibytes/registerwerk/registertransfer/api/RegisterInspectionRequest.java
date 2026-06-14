package de.makibytes.registerwerk.registertransfer.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A §10 eWpG register inspection request. Records who asked to inspect which
 * asset's register, on what basis, and the operator's decision. When fulfilled,
 * the hash of the disclosed extract is retained so the operator can later prove
 * exactly what was disclosed.
 */
@Entity
@Table(name = "register_inspection_request")
public class RegisterInspectionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "requester_entity_id")
    private UUID requesterEntityId;

    @Column(name = "requester_name", nullable = false)
    private String requesterName;

    @Column(name = "requester_email")
    private String requesterEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "legal_basis", nullable = false, length = 30)
    private InspectionLegalBasis legalBasis;

    @Column(name = "stated_interest")
    private String statedInterest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InspectionStatus status = InspectionStatus.REQUESTED;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "content_hash", length = 66)
    private String contentHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }
    public UUID getRequesterEntityId() { return requesterEntityId; }
    public void setRequesterEntityId(UUID requesterEntityId) { this.requesterEntityId = requesterEntityId; }
    public String getRequesterName() { return requesterName; }
    public void setRequesterName(String requesterName) { this.requesterName = requesterName; }
    public String getRequesterEmail() { return requesterEmail; }
    public void setRequesterEmail(String requesterEmail) { this.requesterEmail = requesterEmail; }
    public InspectionLegalBasis getLegalBasis() { return legalBasis; }
    public void setLegalBasis(InspectionLegalBasis legalBasis) { this.legalBasis = legalBasis; }
    public String getStatedInterest() { return statedInterest; }
    public void setStatedInterest(String statedInterest) { this.statedInterest = statedInterest; }
    public InspectionStatus getStatus() { return status; }
    public void setStatus(InspectionStatus status) { this.status = status; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public Instant getFulfilledAt() { return fulfilledAt; }
    public void setFulfilledAt(Instant fulfilledAt) { this.fulfilledAt = fulfilledAt; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
