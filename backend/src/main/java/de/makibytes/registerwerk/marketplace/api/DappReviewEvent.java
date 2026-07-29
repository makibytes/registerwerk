package de.makibytes.registerwerk.marketplace.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Review-trail entry of a dApp version (submit, review start, approve, reject, …). */
@Entity
@Table(name = "dapp_review_event")
public class DappReviewEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "action", nullable = false, length = 30)
    private String action;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
