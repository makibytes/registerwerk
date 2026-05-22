package de.makibytes.registerwerk.kyc.api;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Links a natural person to a legal entity as a beneficial owner (GwG §3, AMLR Art. 42).
 * Threshold: ≥25% direct/indirect ownership or other forms of control.
 */
@Entity
@Table(name = "beneficial_owner")
public class BeneficialOwner {

    public enum ControlType {
        DIRECT_OWNERSHIP,
        INDIRECT_OWNERSHIP,
        OTHER_CONTROL,
        LEGAL_REPRESENTATIVE,
        TRUSTEE,
        NOMINEE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "natural_person_id", nullable = false)
    private UUID naturalPersonId;

    @Column(name = "ownership_pct", precision = 5, scale = 2)
    private BigDecimal ownershipPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_type", nullable = false, length = 30)
    private ControlType controlType;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt = Instant.now();

    @Column(name = "ceased_at")
    private Instant ceasedAt;

    @Column(name = "source")
    private String source;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "notes")
    private String notes;

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID v) { this.entityId = v; }
    public UUID getNaturalPersonId() { return naturalPersonId; }
    public void setNaturalPersonId(UUID v) { this.naturalPersonId = v; }
    public BigDecimal getOwnershipPct() { return ownershipPct; }
    public void setOwnershipPct(BigDecimal v) { this.ownershipPct = v; }
    public ControlType getControlType() { return controlType; }
    public void setControlType(ControlType v) { this.controlType = v; }
    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant v) { this.registeredAt = v; }
    public Instant getCeasedAt() { return ceasedAt; }
    public void setCeasedAt(Instant v) { this.ceasedAt = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public UUID getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(UUID v) { this.verifiedBy = v; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant v) { this.verifiedAt = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
}
