package de.makibytes.registerwerk.blockchain.internal.tx;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Durable signed EVM payload: persisted before, and independently retryable after, broadcast. */
@Entity
@Table(name = "evm_signed_submission")
public class EvmSignedSubmission {

    public enum Status { PREPARED, BROADCAST }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    @Column(name = "chain_id", nullable = false, precision = 78, scale = 0)
    private BigInteger chainId;

    @Column(name = "sender_address", nullable = false, length = 42)
    private String senderAddress;

    @Column(nullable = false, precision = 78, scale = 0)
    private BigInteger nonce;

    @Column(name = "tx_hash", nullable = false, length = 66)
    private String txHash;

    @Column(name = "signed_payload", nullable = false, columnDefinition = "text")
    private String signedPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PREPARED;

    @Column(name = "chain_name", nullable = false, length = 30)
    private String chainName;

    @Column(nullable = false, length = 30)
    private String network;

    @Column(name = "contract_address", nullable = false, length = 42)
    private String contractAddress;

    @Column(name = "method_name", nullable = false, length = 100)
    private String methodName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params;

    @Column(name = "actor_name", length = 255)
    private String actorName;

    @Column(name = "actor_role", length = 30)
    private String actorRole;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "broadcast_at")
    private Instant broadcastAt;

    public UUID getId() { return id; }
    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }
    public BigInteger getChainId() { return chainId; }
    public void setChainId(BigInteger chainId) { this.chainId = chainId; }
    public String getSenderAddress() { return senderAddress; }
    public void setSenderAddress(String senderAddress) { this.senderAddress = senderAddress; }
    public BigInteger getNonce() { return nonce; }
    public void setNonce(BigInteger nonce) { this.nonce = nonce; }
    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }
    public String getSignedPayload() { return signedPayload; }
    public void setSignedPayload(String signedPayload) { this.signedPayload = signedPayload; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getChainName() { return chainName; }
    public void setChainName(String chainName) { this.chainName = chainName; }
    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }
    public String getContractAddress() { return contractAddress; }
    public void setContractAddress(String contractAddress) { this.contractAddress = contractAddress; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
    public String getActorName() { return actorName; }
    public void setActorName(String actorName) { this.actorName = actorName; }
    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getBroadcastAt() { return broadcastAt; }
    public void setBroadcastAt(Instant broadcastAt) { this.broadcastAt = broadcastAt; }
}
