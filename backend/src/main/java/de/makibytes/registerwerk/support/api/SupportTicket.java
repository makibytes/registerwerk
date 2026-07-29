package de.makibytes.registerwerk.support.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer support ticket — previously no support/ticketing concept existed anywhere in the
 * platform; DSAR erasure requests and KYC document review were the only customer-initiated
 * "work item" channels, both narrow and compliance-specific.
 */
@Entity
@Table(name = "support_ticket")
public class SupportTicket {

    public enum Category {
        TECHNICAL, COMPLIANCE, BILLING, ASSET_ISSUE, TRADING, ONBOARDING, OTHER
    }

    public enum Priority {
        LOW, NORMAL, HIGH, URGENT
    }

    public enum Status {
        OPEN, IN_PROGRESS, RESOLVED, CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Priority priority = Priority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "resolution_notes")
    private String resolutionNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID v) { this.entityId = v; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID v) { this.createdBy = v; }
    public String getSubject() { return subject; }
    public void setSubject(String v) { this.subject = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public Category getCategory() { return category; }
    public void setCategory(Category v) { this.category = v; }
    public Priority getPriority() { return priority; }
    public void setPriority(Priority v) { this.priority = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public UUID getAssignedTo() { return assignedTo; }
    public void setAssignedTo(UUID v) { this.assignedTo = v; }
    public String getResolutionNotes() { return resolutionNotes; }
    public void setResolutionNotes(String v) { this.resolutionNotes = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant v) { this.resolvedAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
}
