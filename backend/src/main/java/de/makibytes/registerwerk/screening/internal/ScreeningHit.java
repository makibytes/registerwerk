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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getListSource() { return listSource; }
    public void setListSource(String listSource) { this.listSource = listSource; }
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
}
