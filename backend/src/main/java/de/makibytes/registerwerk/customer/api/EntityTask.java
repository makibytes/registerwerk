package de.makibytes.registerwerk.customer.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Operator work item raised by the entity lifecycle (termination follow-ups, re-KYC after a risk
 * relevant change, chain reinstatement after reactivation). Open tasks are visible and alerting
 * (gauges) until an operator marks them DONE; they are never deleted.
 */
@Entity
@Table(name = "entity_task")
public class EntityTask {

    public enum Status { OPEN, DONE }

    public static final String KYC_REVIEW_REQUIRED = "KYC_REVIEW_REQUIRED";
    public static final String CHAIN_REINSTATEMENT_REQUIRED = "CHAIN_REINSTATEMENT_REQUIRED";
    /** Reinstatement of a CLOSED/DISSOLVED entity: a fresh KYC approval is required (T6-12). */
    public static final String REINSTATEMENT_KYC_REQUIRED = "REINSTATEMENT_KYC_REQUIRED";
    /** Reinstatement: users were disabled at termination; re-enable/re-invite them once KYC is approved (T6-12). */
    public static final String REINSTATEMENT_USERS_REVIEW = "REINSTATEMENT_USERS_REVIEW";
    public static final String SPERRVERMERK_EXPIRY_REVIEW = "SPERRVERMERK_EXPIRY_REVIEW";
    /** A legal block (Sperrvermerk) is in the register but not (yet) enforced on-chain for a wallet (H5). */
    public static final String SPERRVERMERK_FREEZE_NOT_PROPAGATED = "SPERRVERMERK_FREEZE_NOT_PROPAGATED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "kind", nullable = false, length = 48)
    private String kind;

    @Column(name = "ref_id", nullable = false, length = 128)
    private String refId = "";

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private Status status = Status.OPEN;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "done_by")
    private UUID doneBy;

    @Column(name = "done_note", columnDefinition = "text")
    private String doneNote;

    public UUID getId() { return id; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId == null ? "" : refId; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getDoneAt() { return doneAt; }
    public void setDoneAt(Instant doneAt) { this.doneAt = doneAt; }
    public UUID getDoneBy() { return doneBy; }
    public void setDoneBy(UUID doneBy) { this.doneBy = doneBy; }
    public String getDoneNote() { return doneNote; }
    public void setDoneNote(String doneNote) { this.doneNote = doneNote; }
}
