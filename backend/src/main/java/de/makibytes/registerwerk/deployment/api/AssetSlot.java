package de.makibytes.registerwerk.deployment.api;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "asset_slot")
public class AssetSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "slot_id", nullable = false, precision = 78, scale = 0)
    private BigInteger slotId;

    @Column(name = "name", length = 200)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "supply_cap", precision = 78, scale = 0)
    private BigInteger supplyCap;

    @Column(name = "paused", nullable = false)
    private boolean paused = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public BigInteger getSlotId() { return slotId; }
    public void setSlotId(BigInteger slotId) { this.slotId = slotId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public BigInteger getSupplyCap() { return supplyCap; }
    public void setSupplyCap(BigInteger supplyCap) { this.supplyCap = supplyCap; }

    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }

    public Instant getCreatedAt() { return createdAt; }
}
