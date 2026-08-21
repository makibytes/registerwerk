package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.FinalityLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/** The audited, step-up-protected escape hatch — one row per (asset, operation), always wins
 *  over any {@link FinalityPolicyAssignment}. Stays empty normally. */
@Entity
@Table(name = "finality_policy_override",
        uniqueConstraints = @UniqueConstraint(columnNames = {"asset_id", "operation"}))
class FinalityPolicyOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    /** {@code GatedOperation} name — stored as a plain string, not an FK/enum column, matching
     *  {@code chain_effect.effect_type}'s convention (validated at the application layer). */
    @Column(name = "operation", nullable = false, length = 50)
    private String operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "required_level", nullable = false, length = 16)
    private FinalityLevel requiredLevel;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    UUID getId() { return id; }

    UUID getAssetId() { return assetId; }
    void setAssetId(UUID assetId) { this.assetId = assetId; }

    String getOperation() { return operation; }
    void setOperation(String operation) { this.operation = operation; }

    FinalityLevel getRequiredLevel() { return requiredLevel; }
    void setRequiredLevel(FinalityLevel requiredLevel) { this.requiredLevel = requiredLevel; }

    String getReason() { return reason; }
    void setReason(String reason) { this.reason = reason; }

    UUID getCreatedBy() { return createdBy; }
    void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    Instant getCreatedAt() { return createdAt; }
}
