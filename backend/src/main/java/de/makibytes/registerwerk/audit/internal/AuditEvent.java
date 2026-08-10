package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable, append-only audit log (eWpRV §6 integrity). No FK constraints for throughput.
 * Hash chain enforced by AuditEventRecorder: entry_hash = SHA-256(prev_hash || payload || sequence_no).
 * DB-level WORM trigger in V10__audit_chain.sql prevents UPDATE/DELETE.
 */
@Entity
@Table(name = "audit_event")
@Immutable
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "subject_type", nullable = false, length = 50)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_role", length = 30)
    private String actorRole;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    /**
     * audit_event.id of the entry this one reverses/corrects (V2 migration). Null for
     * ordinary events. No FK by design, matching the rest of this table.
     */
    @Column(name = "reverses_event_id", updatable = false)
    private UUID reversesEventId;

    /** Free-form grouping id for audit entries belonging to one logical operation. */
    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    // ── Hash chain fields (V2__audit_chain_and_correction_linkage.sql) ────────
    /**
     * Monotonically increasing sequence number. Reserved explicitly by
     * {@code AuditEventRecorder} (via {@code nextval('audit_event_seq')}) before insert
     * so it is known when the hash chain entry is computed; the column DEFAULT remains
     * as a fallback for any other insert path.
     */
    @Column(name = "sequence_no", updatable = false)
    private Long sequenceNo;

    /** SHA-256 of the previous row's entry_hash; NULL for the genesis row. */
    @Column(name = "prev_hash", updatable = false)
    private byte[] prevHash;

    /** SHA-256(prev_hash || canonical_json(payload) || sequence_no). Populated by AuditEventRecorder. */
    @Column(name = "entry_hash", updatable = false)
    private byte[] entryHash;

    /** Ed25519 signature over entry_hash using the registry signing key (KMS/HSM). */
    @Column(name = "entry_sig", updatable = false)
    private byte[] entrySig;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }

    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }

    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }

    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public Instant getOccurredAt() { return occurredAt; }

    public UUID getReversesEventId() { return reversesEventId; }
    public void setReversesEventId(UUID reversesEventId) { this.reversesEventId = reversesEventId; }

    public UUID getCorrelationId() { return correlationId; }
    public void setCorrelationId(UUID correlationId) { this.correlationId = correlationId; }

    public Long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Long sequenceNo) { this.sequenceNo = sequenceNo; }

    public byte[] getPrevHash() { return prevHash; }
    public void setPrevHash(byte[] prevHash) { this.prevHash = prevHash; }

    public byte[] getEntryHash() { return entryHash; }
    public void setEntryHash(byte[] entryHash) { this.entryHash = entryHash; }

    public byte[] getEntrySig() { return entrySig; }
    public void setEntrySig(byte[] entrySig) { this.entrySig = entrySig; }

    public static AuditEvent from(AuditableEvent e) {
        AuditEvent ae = new AuditEvent();
        ae.setEventType(e.eventType());
        ae.setSubjectType(e.subjectType());
        ae.setSubjectId(e.subjectId());
        ae.setActorId(e.actorId());
        ae.setActorRole(e.actorRole());
        ae.setPayload(withDualControlApprover(e.payload(), e.dualControlApproverId()));
        ae.setReversesEventId(e.reversesEventId());
        ae.setCorrelationId(e.correlationId());
        return ae;
    }

    /**
     * Folds the validated second approver (if any) into the payload under a fixed key —
     * every dual-control-gated event's approver ends up in the same, queryable JSON path
     * regardless of which module published it, instead of each call site inventing its own
     * ad-hoc map key.
     */
    private static Map<String, Object> withDualControlApprover(Map<String, Object> payload, UUID approverId) {
        if (approverId == null) {
            return payload;
        }
        Map<String, Object> merged = payload != null ? new java.util.LinkedHashMap<>(payload) : new java.util.LinkedHashMap<>();
        merged.put("dualControlApproverId", approverId.toString());
        return merged;
    }
}
