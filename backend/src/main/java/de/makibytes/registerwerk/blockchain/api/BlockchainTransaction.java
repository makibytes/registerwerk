package de.makibytes.registerwerk.blockchain.api;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Tracks every on-chain transaction submitted by the registry operator wallet. */
@Entity
@Table(name = "blockchain_transaction")
public class BlockchainTransaction {

    public enum Status { PENDING, SUCCESS, FAILED, TIMEOUT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tx_hash", length = 66)
    private String txHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(length = 30)
    private String chain;

    @Column(length = 30)
    private String network;

    @Column(name = "contract_address", length = 42)
    private String contractAddress;

    @Column(name = "deployment_id")
    private UUID deploymentId;

    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "method_name", length = 100)
    private String methodName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params;

    @Column(name = "actor_name", length = 255)
    private String actorName;

    @Column(name = "actor_role", length = 30)
    private String actorRole;

    @Column(name = "gas_used")
    private Long gasUsed;

    @Column(name = "block_number")
    private Long blockNumber;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public UUID getId() { return id; }

    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getChain() { return chain; }
    public void setChain(String chain) { this.chain = chain; }

    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }

    public String getContractAddress() { return contractAddress; }
    public void setContractAddress(String contractAddress) { this.contractAddress = contractAddress; }

    public UUID getDeploymentId() { return deploymentId; }
    public void setDeploymentId(UUID deploymentId) { this.deploymentId = deploymentId; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }

    public String getActorName() { return actorName; }
    public void setActorName(String actorName) { this.actorName = actorName; }

    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }

    public Long getGasUsed() { return gasUsed; }
    public void setGasUsed(Long gasUsed) { this.gasUsed = gasUsed; }

    public Long getBlockNumber() { return blockNumber; }
    public void setBlockNumber(Long blockNumber) { this.blockNumber = blockNumber; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
