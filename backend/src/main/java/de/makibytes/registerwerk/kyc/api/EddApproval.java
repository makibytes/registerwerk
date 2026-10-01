package de.makibytes.registerwerk.kyc.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Enhanced-due-diligence approval of a confirmed PEP (6-17, parked decision T6-02). Always two
 * different people; {@code reviewDue} is the date after which the person holds the screening gate
 * again until a fresh approval is recorded.
 */
@Entity
@Table(name = "edd_approval")
public class EddApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "natural_person_id", nullable = false)
    private UUID naturalPersonId;

    @Column(name = "approved_by", nullable = false)
    private UUID approvedBy;

    @Column(name = "second_approver_id", nullable = false)
    private UUID secondApproverId;

    @Column(name = "note", nullable = false)
    private String note;

    @Column(name = "review_due", nullable = false)
    private Instant reviewDue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getNaturalPersonId() { return naturalPersonId; }
    public void setNaturalPersonId(UUID v) { this.naturalPersonId = v; }
    public UUID getApprovedBy() { return approvedBy; }
    public void setApprovedBy(UUID v) { this.approvedBy = v; }
    public UUID getSecondApproverId() { return secondApproverId; }
    public void setSecondApproverId(UUID v) { this.secondApproverId = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public Instant getReviewDue() { return reviewDue; }
    public void setReviewDue(Instant v) { this.reviewDue = v; }
    public Instant getCreatedAt() { return createdAt; }
}
