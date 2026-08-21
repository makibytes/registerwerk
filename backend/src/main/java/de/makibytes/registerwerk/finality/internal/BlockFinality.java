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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/** One row per (chain, block) ever observed while it was still in the PROVISIONAL/SAFE
 *  "unsettled" window — see {@code BlockFinalityFeed}'s javadoc for exactly what is and isn't
 *  tracked here. */
@Entity
@Table(name = "block_finality",
        uniqueConstraints = @UniqueConstraint(columnNames = {"chain_config_id", "block_number"}))
class BlockFinality {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    @Column(name = "block_number", nullable = false)
    private long blockNumber;

    @Column(name = "block_hash", length = 128)
    private String blockHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "finality_level", nullable = false, length = 16)
    private FinalityLevel level;

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

    Instant getObservedAt() { return observedAt; }
    void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }

    Instant getUpdatedAt() { return updatedAt; }
}
