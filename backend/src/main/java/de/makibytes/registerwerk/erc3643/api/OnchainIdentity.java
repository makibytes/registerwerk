package de.makibytes.registerwerk.erc3643.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents an ONCHAINID identity contract deployed for a legal entity on a specific chain.
 * Each legal entity may have at most one identity per chain configuration.
 */
@Entity
@Table(
    name = "onchain_identity",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_onchain_identity_entity_chain",
        columnNames = {"legal_entity_id", "chain_config_id"}
    )
)
public class OnchainIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "legal_entity_id", nullable = false)
    private UUID legalEntityId;

    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    /** On-chain address of the deployed ONCHAINID proxy contract. */
    @Column(name = "identity_address", nullable = false, length = 66)
    private String identityAddress;

    @Column(name = "deployed_at")
    private Instant deployedAt;

    @Column(name = "deployed_by_tx", length = 66)
    private String deployedByTx;

    @Column(name = "deployed_block_number")
    private Long deployedBlockNumber;

    @Column(name = "deployed_block_hash", length = 128)
    private String deployedBlockHash;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getLegalEntityId() { return legalEntityId; }
    public void setLegalEntityId(UUID legalEntityId) { this.legalEntityId = legalEntityId; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public String getIdentityAddress() { return identityAddress; }
    public void setIdentityAddress(String identityAddress) { this.identityAddress = identityAddress; }

    public Instant getDeployedAt() { return deployedAt; }
    public void setDeployedAt(Instant deployedAt) { this.deployedAt = deployedAt; }

    public String getDeployedByTx() { return deployedByTx; }
    public void setDeployedByTx(String deployedByTx) { this.deployedByTx = deployedByTx; }

    public Long getDeployedBlockNumber() { return deployedBlockNumber; }
    public void setDeployedBlockNumber(Long deployedBlockNumber) { this.deployedBlockNumber = deployedBlockNumber; }

    public String getDeployedBlockHash() { return deployedBlockHash; }
    public void setDeployedBlockHash(String deployedBlockHash) { this.deployedBlockHash = deployedBlockHash; }
}
