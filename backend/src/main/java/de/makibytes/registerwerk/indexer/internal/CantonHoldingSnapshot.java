package de.makibytes.registerwerk.indexer.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable mirror of a currently-open Canton Holding contract — populated
 * when {@link CantonTransferSyncService} observes the Holding's Created event, and consulted (then
 * deleted) when the same contract's Archived event arrives, since Daml's Archived ledger event
 * carries only the contract ID, never its former argument payload. Without this, an Archived
 * event has no way to recover which instrument/owner/amount was actually consumed.
 */
@Entity
@Table(name = "canton_holding_snapshot", indexes = {
        @Index(name = "idx_canton_holding_snapshot_chain", columnList = "chain_config_id")
})
class CantonHoldingSnapshot {

    @Id
    @Column(name = "contract_id", length = 255)
    private String contractId;

    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    @Column(name = "instrument", nullable = false, length = 255)
    private String instrument;

    @Column(name = "owner", nullable = false, length = 255)
    private String owner;

    @Column(name = "amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    CantonHoldingSnapshot() {}

    CantonHoldingSnapshot(String contractId, UUID chainConfigId, String instrument, String owner, BigDecimal amount) {
        this.contractId = contractId;
        this.chainConfigId = chainConfigId;
        this.instrument = instrument;
        this.owner = owner;
        this.amount = amount;
    }

    String getContractId() { return contractId; }
    UUID getChainConfigId() { return chainConfigId; }
    String getInstrument() { return instrument; }
    String getOwner() { return owner; }
    BigDecimal getAmount() { return amount; }
}
