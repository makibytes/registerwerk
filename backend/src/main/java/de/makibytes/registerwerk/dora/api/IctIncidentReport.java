package de.makibytes.registerwerk.dora.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One authority submission (DORA Art. 19) for an {@link IctIncident}. Append-only: rows are never
 * updated or deleted (database trigger); a correction is a further row.
 */
@Entity
@Table(name = "ict_incident_report")
public class IctIncidentReport {

    public enum Type { INITIAL, INTERMEDIATE, FINAL }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, updatable = false)
    private Type reportType;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "authority_ref", updatable = false)
    private String authorityRef;

    @Column(name = "submitted_by", updatable = false)
    private UUID submittedBy;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String note;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt = Instant.now();

    protected IctIncidentReport() {}

    public IctIncidentReport(UUID incidentId, Type reportType, Instant submittedAt, String authorityRef,
                             UUID submittedBy, String note) {
        this.incidentId = incidentId;
        this.reportType = reportType;
        this.submittedAt = submittedAt;
        this.authorityRef = authorityRef;
        this.submittedBy = submittedBy;
        this.note = note;
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public Type getReportType() { return reportType; }
    public Instant getSubmittedAt() { return submittedAt; }
    public String getAuthorityRef() { return authorityRef; }
    public UUID getSubmittedBy() { return submittedBy; }
    public String getNote() { return note; }
    public Instant getRecordedAt() { return recordedAt; }
}
