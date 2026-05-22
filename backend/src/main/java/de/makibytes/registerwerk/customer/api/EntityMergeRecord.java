package de.makibytes.registerwerk.customer.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Records M&A events for German commercial law retention requirements. */
@Entity
@Table(name = "entity_merge_record")
public class EntityMergeRecord {

    public enum MergeType { ABSORPTION, CONSOLIDATION }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_entity_id", nullable = false)
    private UUID sourceEntityId;

    @Column(name = "target_entity_id", nullable = false)
    private UUID targetEntityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "merge_type", nullable = false, length = 20)
    private MergeType mergeType = MergeType.ABSORPTION;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt = Instant.now();

    @Column(name = "recorded_by")
    private UUID recordedBy;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getSourceEntityId() { return sourceEntityId; }
    public void setSourceEntityId(UUID sourceEntityId) { this.sourceEntityId = sourceEntityId; }

    public UUID getTargetEntityId() { return targetEntityId; }
    public void setTargetEntityId(UUID targetEntityId) { this.targetEntityId = targetEntityId; }

    public MergeType getMergeType() { return mergeType; }
    public void setMergeType(MergeType mergeType) { this.mergeType = mergeType; }

    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getRecordedAt() { return recordedAt; }

    public UUID getRecordedBy() { return recordedBy; }
    public void setRecordedBy(UUID recordedBy) { this.recordedBy = recordedBy; }
}
