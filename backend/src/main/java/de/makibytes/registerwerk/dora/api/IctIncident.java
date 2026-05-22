package de.makibytes.registerwerk.dora.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * DORA Art. 17 ICT-related incident record.
 * Major incidents must be reported to the competent authority within:
 *   - 4 hours of classification as major
 *   - 24 hours initial report (Art. 19 para. 4a)
 *   - 72 hours detailed report (Art. 19 para. 4b)
 *   - 1 month final report (Art. 19 para. 4c)
 */
@Entity
@Table(name = "ict_incident")
public class IctIncident {

    public enum Category { DATA_BREACH, SYSTEM_OUTAGE, RANSOMWARE, THIRD_PARTY_FAILURE, OTHER }
    public enum Severity { LOW, MEDIUM, HIGH, MAJOR }
    public enum Status {
        DETECTED, INVESTIGATING, CONTAINED, RESOLVED,
        REPORTED_TO_AUTHORITY, CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DETECTED;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "source_event_type")
    private String sourceEventType;

    @Column(name = "source_event_ref")
    private UUID sourceEventRef;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();

    /** 24-hour initial notification deadline (DORA Art. 19 para. 4a). */
    @Column(name = "initial_report_deadline")
    private Instant initialReportDeadline;

    /** 72-hour detailed report deadline (DORA Art. 19 para. 4b). */
    @Column(name = "final_report_deadline")
    private Instant finalReportDeadline;

    @Column(name = "initial_reported_at")
    private Instant initialReportedAt;

    @Column(name = "final_reported_at")
    private Instant finalReportedAt;

    @Column(name = "authority_ref")
    private String authorityRef;

    @Column(name = "contained_at")
    private Instant containedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "root_cause", columnDefinition = "TEXT")
    private String rootCause;

    @Column(name = "remediation_steps", columnDefinition = "TEXT")
    private String remediationSteps;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSourceEventType() { return sourceEventType; }
    public void setSourceEventType(String t) { this.sourceEventType = t; }
    public UUID getSourceEventRef() { return sourceEventRef; }
    public void setSourceEventRef(UUID r) { this.sourceEventRef = r; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant t) { this.detectedAt = t; }
    public Instant getInitialReportDeadline() { return initialReportDeadline; }
    public void setInitialReportDeadline(Instant t) { this.initialReportDeadline = t; }
    public Instant getFinalReportDeadline() { return finalReportDeadline; }
    public void setFinalReportDeadline(Instant t) { this.finalReportDeadline = t; }
    public Instant getInitialReportedAt() { return initialReportedAt; }
    public void setInitialReportedAt(Instant t) { this.initialReportedAt = t; }
    public Instant getFinalReportedAt() { return finalReportedAt; }
    public void setFinalReportedAt(Instant t) { this.finalReportedAt = t; }
    public String getAuthorityRef() { return authorityRef; }
    public void setAuthorityRef(String r) { this.authorityRef = r; }
    public Instant getContainedAt() { return containedAt; }
    public void setContainedAt(Instant t) { this.containedAt = t; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant t) { this.resolvedAt = t; }
    public String getRootCause() { return rootCause; }
    public void setRootCause(String s) { this.rootCause = s; }
    public String getRemediationSteps() { return remediationSteps; }
    public void setRemediationSteps(String s) { this.remediationSteps = s; }
    public UUID getAssignedTo() { return assignedTo; }
    public void setAssignedTo(UUID u) { this.assignedTo = u; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID u) { this.createdBy = u; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
