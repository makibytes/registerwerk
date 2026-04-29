package de.makibytes.registerwerk.domain.wallet;

import de.makibytes.registerwerk.domain.chain.ChainConfig;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps a chain to its currently active default signing wallet.
 * The PK on {@code chain_config_id} enforces exactly one default per chain.
 */
@Entity
@Table(name = "wallet_chain_default")
public class WalletChainDefault {

    @Id
    @Column(name = "chain_config_id")
    private UUID chainConfigId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chain_config_id", insertable = false, updatable = false)
    private ChainConfig chainConfig;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private OperatorWallet wallet;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @PreUpdate
    @PrePersist
    void onSave() { this.updatedAt = Instant.now(); }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public ChainConfig getChainConfig() { return chainConfig; }

    public OperatorWallet getWallet() { return wallet; }
    public void setWallet(OperatorWallet wallet) { this.wallet = wallet; }

    public Instant getUpdatedAt() { return updatedAt; }

    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
