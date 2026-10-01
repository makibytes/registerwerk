package de.makibytes.registerwerk.screening.internal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "screening_hit")
public class ScreeningHit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "list_source", nullable = false)
    private String listSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private HitCategory category = HitCategory.SANCTIONS;

    @Column(name = "matched_field", nullable = false)
    private String matchedField;

    @Column(name = "matched_value", nullable = false)
    private String matchedValue;

    @Column(name = "match_score", precision = 5, scale = 2)
    private BigDecimal matchScore;

    @Column(name = "accepted")
    private Boolean accepted;

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accept_reason")
    private String acceptReason;

    @Column(name = "dual_control_approver_id")
    private UUID dualControlApproverId;

    @Column(name = "dual_control_approved_at")
    private Instant dualControlApprovedAt;

    @Column(name = "external_id")
    private String externalId;

    /** sha256 over provider, list, provider record id and normalised matched value; see {@link HitFingerprint}. */
    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    /** The original (non-carried) hit whose decision this hit inherited, if any. */
    @Column(name = "carried_from_hit_id")
    private UUID carriedFromHitId;

    @Column(name = "carried_at")
    private Instant carriedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 20)
    private HitResolution resolution;

    @Column(name = "pep_confirmed_by")
    private UUID pepConfirmedBy;

    @Column(name = "pep_confirmed_at")
    private Instant pepConfirmedAt;

    @Column(name = "pep_confirm_note")
    private String pepConfirmNote;

    @Column(name = "edd_approval_id")
    private UUID eddApprovalId;

    @Column(name = "edd_approved_at")
    private Instant eddApprovedAt;

    @Column(name = "edd_review_due")
    private Instant eddReviewDue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getListSource() { return listSource; }
    public void setListSource(String listSource) { this.listSource = listSource; }
    public HitCategory getCategory() { return category; }
    public void setCategory(HitCategory category) { this.category = category; }
    public String getMatchedField() { return matchedField; }
    public void setMatchedField(String matchedField) { this.matchedField = matchedField; }
    public String getMatchedValue() { return matchedValue; }
    public void setMatchedValue(String matchedValue) { this.matchedValue = matchedValue; }
    public BigDecimal getMatchScore() { return matchScore; }
    public void setMatchScore(BigDecimal matchScore) { this.matchScore = matchScore; }
    public Boolean getAccepted() { return accepted; }
    public void setAccepted(Boolean accepted) { this.accepted = accepted; }
    public UUID getAcceptedBy() { return acceptedBy; }
    public void setAcceptedBy(UUID acceptedBy) { this.acceptedBy = acceptedBy; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }
    public String getAcceptReason() { return acceptReason; }
    public void setAcceptReason(String acceptReason) { this.acceptReason = acceptReason; }
    public UUID getDualControlApproverId() { return dualControlApproverId; }
    public void setDualControlApproverId(UUID id) { this.dualControlApproverId = id; }
    public Instant getDualControlApprovedAt() { return dualControlApprovedAt; }
    public void setDualControlApprovedAt(Instant t) { this.dualControlApprovedAt = t; }
    public Instant getCreatedAt() { return createdAt; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public UUID getCarriedFromHitId() { return carriedFromHitId; }
    public void setCarriedFromHitId(UUID id) { this.carriedFromHitId = id; }
    public Instant getCarriedAt() { return carriedAt; }
    public void setCarriedAt(Instant t) { this.carriedAt = t; }
    public HitResolution getResolution() { return resolution; }
    public void setResolution(HitResolution resolution) { this.resolution = resolution; }
    public UUID getPepConfirmedBy() { return pepConfirmedBy; }
    public void setPepConfirmedBy(UUID id) { this.pepConfirmedBy = id; }
    public Instant getPepConfirmedAt() { return pepConfirmedAt; }
    public void setPepConfirmedAt(Instant t) { this.pepConfirmedAt = t; }
    public String getPepConfirmNote() { return pepConfirmNote; }
    public void setPepConfirmNote(String note) { this.pepConfirmNote = note; }
    public UUID getEddApprovalId() { return eddApprovalId; }
    public void setEddApprovalId(UUID id) { this.eddApprovalId = id; }
    public Instant getEddApprovedAt() { return eddApprovedAt; }
    public void setEddApprovedAt(Instant t) { this.eddApprovedAt = t; }
    public Instant getEddReviewDue() { return eddReviewDue; }
    public void setEddReviewDue(Instant t) { this.eddReviewDue = t; }

    /** A confirmed PEP whose enhanced due diligence was approved and whose review is not yet due. */
    public boolean eddInForce(Instant now) {
        return resolution == HitResolution.CONFIRMED_PEP && eddApprovedAt != null
                && (eddReviewDue == null || eddReviewDue.isAfter(now));
    }

    /** Whether this hit keeps the gate closed: not accepted as a false positive and no valid EDD cover. */
    public boolean blocksGate(Instant now) {
        return !Boolean.TRUE.equals(accepted) && !eddInForce(now);
    }
}
