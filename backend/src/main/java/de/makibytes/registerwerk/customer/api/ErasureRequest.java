package de.makibytes.registerwerk.customer.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A persisted DSGVO Art. 17 erasure request. Previously the erasure endpoint returned
 * "ERASURE_REQUESTED" without storing anything, so a legally-acknowledged request was
 * silently dropped and no operator could ever action it. This record is the operator
 * work item: it carries the 30-day response clock (Art. 12(3)) and the resolution.
 */
@Entity
@Table(name = "erasure_request")
public class ErasureRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    /** The authenticated user (sub) who filed the request, when known. */
    @Column(name = "requested_by_user_id")
    private UUID requestedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ErasureRequestStatus status = ErasureRequestStatus.REQUESTED;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt = Instant.now();

    /** DSGVO Art. 12(3): the request must be answered within one month. */
    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }

    public UUID getRequestedByUserId() { return requestedByUserId; }
    public void setRequestedByUserId(UUID requestedByUserId) { this.requestedByUserId = requestedByUserId; }

    public ErasureRequestStatus getStatus() { return status; }
    public void setStatus(ErasureRequestStatus status) { this.status = status; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }

    public UUID getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(UUID reviewedBy) { this.reviewedBy = reviewedBy; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }
}
