package de.makibytes.registerwerk.repo.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Opt-in of a company to the repo desk ({@code listed} controls appearance in the directory). */
@Entity
@Table(name = "repo_desk_participant")
public class RepoDeskParticipant {
    @Id @Column(name = "entity_id") private UUID entityId;
    @Column(name = "opted_in_at", nullable = false) private Instant optedInAt = Instant.now();
    @Column(name = "opted_in_by") private UUID optedInBy;
    @Column(nullable = false) private boolean listed;
    @Column(name = "opted_out_at") private Instant optedOutAt;

    public UUID getEntityId() { return entityId; } public void setEntityId(UUID v) { entityId = v; }
    public Instant getOptedInAt() { return optedInAt; } public void setOptedInAt(Instant v) { optedInAt = v; }
    public UUID getOptedInBy() { return optedInBy; } public void setOptedInBy(UUID v) { optedInBy = v; }
    public boolean isListed() { return listed; } public void setListed(boolean v) { listed = v; }
    public Instant getOptedOutAt() { return optedOutAt; } public void setOptedOutAt(Instant v) { optedOutAt = v; }
    public boolean isActive() { return optedOutAt == null; }
}
