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

    /**
     * PREPARED = signed, not yet accepted by a node; BROADCAST = visible to a node; SUPERSEDED = a
     * replacement (re-price / cancel) at the same nonce exists, the original may still be mined;
     * ABANDONED = the nonce is proven consumed by a different transaction, this payload can never mine.
     */
    public enum Status { PREPARED, BROADCAST, SUPERSEDED, ABANDONED }

    /** OPERATION = the business call; REPRICE = same call, higher fee; CANCEL = 0-value self-send. */
    public enum Kind { OPERATION, REPRICE, CANCEL }

    /** Classification of the last broadcast failure (see {@code BroadcastErrorClassifier}). */
    public enum ErrorClass { UNDERPRICED, BASE_FEE, INSUFFICIENT_FUNDS, NONCE_LOW, INVALID, TRANSPORT, OTHER }

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

    /** P4B-7: '<scope>:<scopeId>:<Idempotency-Key>#<n>' of the HTTP request that caused this
     *  submission; unique on the outbox row so a replayed request maps to this very transaction. */
    @Column(name = "idempotency_key", length = 400)
    private String idempotencyKey;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "broadcast_at")
    private Instant broadcastAt;

    @Column(name = "first_failed_at")
    private Instant firstFailedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_error_class", length = 30)
    private ErrorClass lastErrorClass;

    /** Back-off: the dispatcher does not retry this signer's head row before this instant. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind = Kind.OPERATION;

    @Column(name = "replaces_tx_hash", length = 66)
    private String replacesTxHash;

    @Column(name = "superseded_by_tx_hash", length = 66)
    private String supersededByTxHash;

    @Column(name = "abandoned_at")
    private Instant abandonedAt;

    @Column(name = "abandoned_by", length = 255)
    private String abandonedBy;

    @Column(name = "abandon_approver_id")
    private java.util.UUID abandonApproverId;

    @Column(name = "abandon_reason", columnDefinition = "text")
    private String abandonReason;

    @Column(name = "last_rebroadcast_at")
    private Instant lastRebroadcastAt;

    @Column(name = "rebroadcast_count", nullable = false)
    private int rebroadcastCount;

    @Column(name = "stuck_alerted_at")
    private Instant stuckAlertedAt;

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
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getBroadcastAt() { return broadcastAt; }
    public void setBroadcastAt(Instant broadcastAt) { this.broadcastAt = broadcastAt; }
    public Instant getFirstFailedAt() { return firstFailedAt; }
    public void setFirstFailedAt(Instant firstFailedAt) { this.firstFailedAt = firstFailedAt; }
    public ErrorClass getLastErrorClass() { return lastErrorClass; }
    public void setLastErrorClass(ErrorClass lastErrorClass) { this.lastErrorClass = lastErrorClass; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public String getReplacesTxHash() { return replacesTxHash; }
    public void setReplacesTxHash(String replacesTxHash) { this.replacesTxHash = replacesTxHash; }
    public String getSupersededByTxHash() { return supersededByTxHash; }
    public void setSupersededByTxHash(String supersededByTxHash) { this.supersededByTxHash = supersededByTxHash; }
    public Instant getAbandonedAt() { return abandonedAt; }
    public void setAbandonedAt(Instant abandonedAt) { this.abandonedAt = abandonedAt; }
    public String getAbandonedBy() { return abandonedBy; }
    public void setAbandonedBy(String abandonedBy) { this.abandonedBy = abandonedBy; }
    public java.util.UUID getAbandonApproverId() { return abandonApproverId; }
    public void setAbandonApproverId(java.util.UUID abandonApproverId) { this.abandonApproverId = abandonApproverId; }
    public String getAbandonReason() { return abandonReason; }
    public void setAbandonReason(String abandonReason) { this.abandonReason = abandonReason; }
    public Instant getLastRebroadcastAt() { return lastRebroadcastAt; }
    public void setLastRebroadcastAt(Instant lastRebroadcastAt) { this.lastRebroadcastAt = lastRebroadcastAt; }
    public int getRebroadcastCount() { return rebroadcastCount; }
    public void setRebroadcastCount(int rebroadcastCount) { this.rebroadcastCount = rebroadcastCount; }
    public Instant getStuckAlertedAt() { return stuckAlertedAt; }
    public void setStuckAlertedAt(Instant stuckAlertedAt) { this.stuckAlertedAt = stuckAlertedAt; }
}
