package de.makibytes.registerwerk.domain.wallet;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadata record for an operator-managed signing wallet.
 *
 * <p>No private-key material is stored here. The actual encrypted keystore lives
 * at {@link #keystorePath} on the backend's docker volume. Decryption uses the
 * master KEK from {@code registerwerk.wallet.master-key} (env-only).
 */
@Entity
@Table(name = "operator_wallet")
public class OperatorWallet {

    public enum WalletType { EVM, SOLANA, CANTON }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Operator-chosen display name. Must be unique. */
    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private WalletType type;

    /** Checksummed EVM address (0x…), base58 Solana address, or Canton party ID (Name::fingerprint). */
    @Column(nullable = false, length = 300)
    private String address;

    /** Path to the encrypted keystore file, relative to the configured storage root. */
    @Column(name = "keystore_path", nullable = false, length = 255)
    private String keystorePath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "created_by")
    private UUID createdBy;

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public WalletType getType() { return type; }
    public void setType(WalletType type) { this.type = type; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getKeystorePath() { return keystorePath; }
    public void setKeystorePath(String keystorePath) { this.keystorePath = keystorePath; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
