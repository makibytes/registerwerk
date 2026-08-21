package de.makibytes.registerwerk.deployment.api;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset_deployment")
public class AssetDeployment {

    public enum DeploymentStatus { PENDING, CONFIRMED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Chain chain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Network network;

    @Column(name = "contract_address", length = 66)
    private String contractAddress;

    @Column(name = "deployed_at")
    private Instant deployedAt;

    @Column(name = "deployed_by_tx", length = 66)
    private String deployedByTx;

    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_status", nullable = false, length = 10)
    private DeploymentStatus deploymentStatus = DeploymentStatus.PENDING;

    /** EVM deployment transaction receipt block hash. */
    @Column(name = "block_hash", length = 66)
    private String blockHash;

    @Column(name = "block_number")
    private Long blockNumber;

    /** FK to {@code chain_config} — lets the reorg-retraction sweep
     *  ({@code finality.internal.BlockFinalityServiceImpl#recordRetraction}) find affected rows by
     *  (chainConfigId, blockNumber). Set only on the EVM confirmation path (the only path that also
     *  records {@link #blockNumber}); null for Starknet/Stellar/Solana/Canton deployments and rows
     *  written before this column existed. */
    @Column(name = "chain_config_id")
    private UUID chainConfigId;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public Chain getChain() { return chain; }
    public void setChain(Chain chain) { this.chain = chain; }

    public Network getNetwork() { return network; }
    public void setNetwork(Network network) { this.network = network; }

    public String getContractAddress() { return contractAddress; }
    public void setContractAddress(String contractAddress) { this.contractAddress = contractAddress; }

    public Instant getDeployedAt() { return deployedAt; }
    public void setDeployedAt(Instant deployedAt) { this.deployedAt = deployedAt; }

    public String getDeployedByTx() { return deployedByTx; }
    public void setDeployedByTx(String deployedByTx) { this.deployedByTx = deployedByTx; }

    public DeploymentStatus getDeploymentStatus() { return deploymentStatus; }
    public void setDeploymentStatus(DeploymentStatus deploymentStatus) { this.deploymentStatus = deploymentStatus; }

    public String getBlockHash() { return blockHash; }
    public void setBlockHash(String blockHash) { this.blockHash = blockHash; }

    public Long getBlockNumber() { return blockNumber; }
    public void setBlockNumber(Long blockNumber) { this.blockNumber = blockNumber; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }
}
