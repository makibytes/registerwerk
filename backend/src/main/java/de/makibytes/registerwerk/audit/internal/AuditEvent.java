package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Immutable, append-only audit log. No FK constraints for throughput. */
@Entity
@Table(name = "audit_event")
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

    public static AuditEvent from(AuditableEvent e) {
        AuditEvent ae = new AuditEvent();
        ae.setEventType(e.eventType());
        ae.setSubjectType(e.subjectType());
        ae.setSubjectId(e.subjectId());
        ae.setActorId(e.actorId());
        ae.setActorRole(e.actorRole());
        ae.setPayload(e.payload());
        return ae;
    }
}
