package de.makibytes.registerwerk.indexer.internal;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-mint polling cursor for {@link SolanaTransferSyncService} — the
 * shared, chain-level {@code indexer_state} row is unsuitable here because a single Solana chain
 * tracks many independent SPL mints, each with its own signature history; reusing one cursor
 * across all of them meant one mint's pagination boundary was silently applied to every other
 * mint's poll.
 */
@Entity
@Table(
    name = "solana_mint_sync_cursor",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_solana_mint_sync_cursor",
        columnNames = {"chain_config_id", "mint_address"}
    )
)
class SolanaMintSyncCursor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    @Column(name = "mint_address", nullable = false, length = 64)
    private String mintAddress;

    @Column(name = "last_synced_signature", length = 200)
    private String lastSyncedSignature;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    UUID getChainConfigId() { return chainConfigId; }
    void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    String getMintAddress() { return mintAddress; }
    void setMintAddress(String mintAddress) { this.mintAddress = mintAddress; }

    String getLastSyncedSignature() { return lastSyncedSignature; }
    void setLastSyncedSignature(String lastSyncedSignature) { this.lastSyncedSignature = lastSyncedSignature; }

    Instant getLastSyncedAt() { return lastSyncedAt; }
    void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
}
