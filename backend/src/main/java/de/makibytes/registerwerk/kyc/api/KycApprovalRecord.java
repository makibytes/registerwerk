package de.makibytes.registerwerk.kyc.api;

import de.makibytes.registerwerk.customer.api.Jurisdiction;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Evidence snapshot written with every entity-level KYC approval (6-15). */
@Entity
@Table(name = "kyc_approval_record")
public class KycApprovalRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "second_approver_id")
    private UUID secondApproverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "jurisdiction", nullable = false, length = 20)
    private Jurisdiction jurisdiction;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "checklist_compliant", nullable = false)
    private boolean checklistCompliant;

    @Column(name = "override_note")
    private String overrideNote;

    @Column(name = "identified_pct", nullable = false)
    private BigDecimal identifiedPct = BigDecimal.ZERO;

    @Column(name = "smo_fallback", nullable = false)
    private boolean smoFallback;

    @Column(name = "evidence_snapshot")
    private String evidenceSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID v) { this.entityId = v; }
    public UUID getApprovedBy() { return approvedBy; }
    public void setApprovedBy(UUID v) { this.approvedBy = v; }
    public UUID getSecondApproverId() { return secondApproverId; }
    public void setSecondApproverId(UUID v) { this.secondApproverId = v; }
    public Jurisdiction getJurisdiction() { return jurisdiction; }
    public void setJurisdiction(Jurisdiction v) { this.jurisdiction = v; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate v) { this.expiryDate = v; }
    public boolean isChecklistCompliant() { return checklistCompliant; }
    public void setChecklistCompliant(boolean v) { this.checklistCompliant = v; }
    public String getOverrideNote() { return overrideNote; }
    public void setOverrideNote(String v) { this.overrideNote = v; }
    public BigDecimal getIdentifiedPct() { return identifiedPct; }
    public void setIdentifiedPct(BigDecimal v) { this.identifiedPct = v; }
    public boolean isSmoFallback() { return smoFallback; }
    public void setSmoFallback(boolean v) { this.smoFallback = v; }
    public String getEvidenceSnapshot() { return evidenceSnapshot; }
    public void setEvidenceSnapshot(String v) { this.evidenceSnapshot = v; }
    public Instant getCreatedAt() { return createdAt; }
}
