package de.makibytes.registerwerk.blockchain.internal.tx;

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

    /**
     * TIMEOUT is <em>not</em> a confirmed failure: the transaction was not mined within the timeout
     * but may still be (the poller keeps reading TIMEOUT rows for the late-mined window). Only FAILED
     * (reverted receipt) and REPLACED (the nonce was consumed by a different transaction, so this one
     * can never mine) mean "it did not and will not happen".
     */
    public enum Status { PENDING, SUCCESS, FAILED, TIMEOUT, REPLACED }

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

    /** FK to {@code chain_config} — lets the reorg-retraction sweep
     *  ({@code finality.internal.BlockFinalityServiceImpl#recordRetraction}) find affected rows by
     *  (chainConfigId, blockNumber) directly, rather than string-matching {@link #chain}/{@link #network}.
     *  Null for rows written before this column existed, or if the chain+network couldn't be
     *  resolved to a known {@code ChainConfig} at submission time. */
    @Column(name = "chain_config_id")
    private UUID chainConfigId;

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

    /** P4B-7: '<scope>:<scopeId>:<Idempotency-Key>#<n>' of the HTTP request that caused this
     *  submission; unique on the outbox row so a replayed request maps to this very transaction. */
    @Column(name = "idempotency_key", length = 400)
    private String idempotencyKey;

    @Column(name = "gas_used")
    private Long gasUsed;

    @Column(name = "block_number")
    private Long blockNumber;

    /**
     * Receipt block hash, recorded the first poll a receipt is seen and re-verified on every
     * subsequent poll before the confirmation count is trusted (reorg guard). Null until a
     * receipt has been observed at least once.
     */
    @Column(name = "block_hash", length = 66)
    private String blockHash;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Operator annotation for a FAILED/TIMEOUT transaction, once handled — usually out-of-band
     * via the chain's own tooling, since TIMEOUT is currently terminal (see the global
     * transaction console's Javadoc for why an automated gas-bump resubmit isn't implemented).
     */
    @Column(name = "ops_note", columnDefinition = "text")
    private String opsNote;

    @Column(name = "ops_reviewed_at")
    private Instant opsReviewedAt;

    @Column(name = "ops_reviewed_by")
    private UUID opsReviewedBy;

    /** P4C-4: validated second approver (4-eyes) of the request that submitted this tx; null when none was required. */
    @Column(name = "approver_id")
    private UUID approverId;

    /** P4C-4: optional free-text case / order reference supplied with the request (parked T4-06). */
    @Column(name = "case_reference", length = 200)
    private String caseReference;

    /** When a receipt finally arrived for a row that had already been marked TIMEOUT (P4B-5). */
    @Column(name = "late_mined_at")
    private Instant lateMinedAt;

    /** Latest re-price / cancel replacement issued for this transaction's nonce (P4B-4). */
    @Column(name = "replaced_by_tx_hash", length = 66)
    private String replacedByTxHash;

    /** The hash that actually mined, when it differs from {@link #txHash} (a re-priced replacement). */
    @Column(name = "mined_tx_hash", length = 66)
    private String minedTxHash;

    /**
     * Verified business outcome of a confidential forced operation, written by
     * {@code ConfidentialForcedOpVerifier}. {@link #status} SUCCESS only says the transaction did
     * not revert; a confidential forcedTransfer/confidentialBurn with insufficient balance moves 0
     * and still succeeds. Null while not applicable or still being verified.
     */
    @Column(name = "execution_outcome", length = 40)
    private String executionOutcome;

    @Column(name = "execution_outcome_attempts", nullable = false)
    private int executionOutcomeAttempts;

    @Column(name = "execution_outcome_checked_at")
    private Instant executionOutcomeCheckedAt;

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public UUID getId() { return id; }

    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }

    public Instant getLateMinedAt() { return lateMinedAt; }
    public void setLateMinedAt(Instant lateMinedAt) { this.lateMinedAt = lateMinedAt; }
    public String getReplacedByTxHash() { return replacedByTxHash; }
    public void setReplacedByTxHash(String replacedByTxHash) { this.replacedByTxHash = replacedByTxHash; }
    public String getMinedTxHash() { return minedTxHash; }
    public void setMinedTxHash(String minedTxHash) { this.minedTxHash = minedTxHash; }
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

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

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
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Long getGasUsed() { return gasUsed; }
    public void setGasUsed(Long gasUsed) { this.gasUsed = gasUsed; }

    public Long getBlockNumber() { return blockNumber; }
    public void setBlockNumber(Long blockNumber) { this.blockNumber = blockNumber; }

    public String getBlockHash() { return blockHash; }
    public void setBlockHash(String blockHash) { this.blockHash = blockHash; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getOpsNote() { return opsNote; }
    public void setOpsNote(String opsNote) { this.opsNote = opsNote; }

    public Instant getOpsReviewedAt() { return opsReviewedAt; }
    public void setOpsReviewedAt(Instant opsReviewedAt) { this.opsReviewedAt = opsReviewedAt; }

    public UUID getApproverId() { return approverId; }
    public void setApproverId(UUID approverId) { this.approverId = approverId; }

    public String getCaseReference() { return caseReference; }
    public void setCaseReference(String caseReference) { this.caseReference = caseReference; }

    public UUID getOpsReviewedBy() { return opsReviewedBy; }
    public void setOpsReviewedBy(UUID opsReviewedBy) { this.opsReviewedBy = opsReviewedBy; }

    public String getExecutionOutcome() { return executionOutcome; }
    public void setExecutionOutcome(String executionOutcome) { this.executionOutcome = executionOutcome; }

    public int getExecutionOutcomeAttempts() { return executionOutcomeAttempts; }
    public void setExecutionOutcomeAttempts(int executionOutcomeAttempts) { this.executionOutcomeAttempts = executionOutcomeAttempts; }

    public Instant getExecutionOutcomeCheckedAt() { return executionOutcomeCheckedAt; }
    public void setExecutionOutcomeCheckedAt(Instant executionOutcomeCheckedAt) { this.executionOutcomeCheckedAt = executionOutcomeCheckedAt; }
}
