package de.makibytes.registerwerk.asset.api;

import jakarta.persistence.*;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset_token_unit")
public class AssetTokenUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "slot_id", nullable = false, precision = 78, scale = 0)
    private BigInteger slotId;

    @Column(name = "token_id", nullable = false, precision = 78, scale = 0)
    private BigInteger tokenId;

    @Column(name = "owner_addr", length = 80)
    private String ownerAddr;

    @Column(name = "token_value", nullable = false, precision = 78, scale = 0)
    private BigInteger tokenValue = BigInteger.ZERO;

    @Column(name = "frozen", nullable = false)
    private boolean frozen = false;

    @Column(name = "freeze_reason", columnDefinition = "TEXT")
    private String freezeReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public BigInteger getSlotId() { return slotId; }
    public void setSlotId(BigInteger slotId) { this.slotId = slotId; }

    public BigInteger getTokenId() { return tokenId; }
    public void setTokenId(BigInteger tokenId) { this.tokenId = tokenId; }

    public String getOwnerAddr() { return ownerAddr; }
    public void setOwnerAddr(String ownerAddr) { this.ownerAddr = ownerAddr; }

    public BigInteger getTokenValue() { return tokenValue; }
    public void setTokenValue(BigInteger tokenValue) { this.tokenValue = tokenValue; }

    public boolean isFrozen() { return frozen; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }

    public String getFreezeReason() { return freezeReason; }
    public void setFreezeReason(String freezeReason) { this.freezeReason = freezeReason; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
