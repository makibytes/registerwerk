package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.FinalityLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One immutable-identity block incarnation. A height may accumulate several rows across reorgs,
 *  but the database permits only one canonical incarnation at that height. */
@Entity
@Table(name = "block_finality")
class BlockFinality {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chain_config_id", nullable = false, updatable = false)
    private UUID chainConfigId;

    @Column(name = "block_number", nullable = false, updatable = false)
    private long blockNumber;

    @Column(name = "block_hash", length = 128, updatable = false)
    private String blockHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "finality_level", nullable = false, length = 16)
    private FinalityLevel level;

    @Column(name = "canonical", nullable = false)
    private boolean canonical = true;

    /** Most recent time this incarnation stopped being canonical. Complete episode history lives
     *  in the auditable BlockRetractedEvent stream rather than being collapsed into this row. */
    @Column(name = "orphaned_at")
    private Instant orphanedAt;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    UUID getId() { return id; }

    UUID getChainConfigId() { return chainConfigId; }
    void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    long getBlockNumber() { return blockNumber; }
    void setBlockNumber(long blockNumber) { this.blockNumber = blockNumber; }

    String getBlockHash() { return blockHash; }
    void setBlockHash(String blockHash) { this.blockHash = blockHash; }

    FinalityLevel getLevel() { return level; }
    void setLevel(FinalityLevel level) { this.level = level; }

    boolean isCanonical() { return canonical; }
    void setCanonical(boolean canonical) { this.canonical = canonical; }

    Instant getOrphanedAt() { return orphanedAt; }
    void setOrphanedAt(Instant orphanedAt) { this.orphanedAt = orphanedAt; }

    Instant getObservedAt() { return observedAt; }
    void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }

    Instant getUpdatedAt() { return updatedAt; }
}
