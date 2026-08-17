package de.makibytes.registerwerk.indexer.api;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted record of a single on-chain token transfer event (mint, transfer, or burn).
 * Covers both EVM (ERC-20/721/1155) and Solana SPL token programs.
 */
@Entity
@Table(
    name = "token_transfer",
    indexes = {
        @Index(name = "idx_tt_chain_config_block", columnList = "chain_config_id, block_number"),
        @Index(name = "idx_tt_contract_address",   columnList = "contract_address"),
        @Index(name = "idx_tt_asset_id",           columnList = "asset_id"),
        @Index(name = "idx_tt_deployment_id",      columnList = "deployment_id"),
        @Index(name = "idx_tt_dedup",              columnList = "chain_config_id, tx_hash, log_index", unique = true)
    }
)
public class TokenTransfer {

    public enum EventType { MINT, TRANSFER, BURN }

    /**
     * Two-tier finality state.
     * PROVISIONAL: within the configured confirmation depth, not yet re-verified.
     * FINAL: cleared the confirmation depth, or chain-natively final on write
     * (Solana at commitment=finalized, Stellar ledger close, Canton synchronizer commit,
     * Starknet ACCEPTED_ON_L1).
     * ORPHANED: re-verification found this row's block/tx is no longer canonical. Rows are
     * never deleted, only marked — this is a regulated register with an audit-trail requirement.
     */
    public enum FinalityStatus { PROVISIONAL, FINAL, ORPHANED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** FK to asset — may be null if the contract address has not yet been linked to an asset. */
    @Column(name = "asset_id")
    private UUID assetId;

    /** FK to asset_deployment — may be null for the same reason as assetId. */
    @Column(name = "deployment_id")
    private UUID deploymentId;

    /** FK to chain_config. Non-null — every transfer belongs to a known chain. */
    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    @Column(name = "contract_address", nullable = false, length = 128)
    private String contractAddress;

    /** Sender address (null for mints where the token is created from thin air). */
    @Column(name = "from_address", length = 128)
    private String fromAddress;

    /** Recipient address (null for burns). */
    @Column(name = "to_address", length = 128)
    private String toAddress;

    /** Token ID for ERC-721 / ERC-1155. Null for ERC-20. */
    @Column(name = "token_id", precision = 78, scale = 0)
    private BigDecimal tokenId;

    /** Transfer amount. For ERC-721, typically 1. For ERC-1155 or ERC-20, the actual quantity. */
    @Column(name = "amount", precision = 78, scale = 18)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 10)
    private EventType eventType;

    @Column(name = "tx_hash", nullable = false, length = 128)
    private String txHash;

    @Column(name = "block_number")
    private Long blockNumber;

    /** EVM log index within the transaction. Null for Solana. */
    @Column(name = "log_index")
    private Integer logIndex;

    /** Solana slot number. Null for EVM. */
    @Column(name = "slot")
    private Long slot;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Full URL to the transaction in the chain's block explorer. */
    @Column(name = "explorer_tx_url", length = 512)
    private String explorerTxUrl;

    /** Raw event payload stored as JSONB for auditability and future re-parsing. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_data", columnDefinition = "jsonb")
    private Map<String, Object> rawData;

    /**
     * Defaults to FINAL, matching the DB column default — correct for chains that are final on
     * write (Solana/Stellar/Canton, see class javadoc). EVM (via GraphNodeSyncService) and
     * Starknet explicitly set PROVISIONAL at write time when the row is within the confirmation
     * depth / not yet L1-accepted.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "finality_status", nullable = false, length = 12)
    private FinalityStatus finalityStatus = FinalityStatus.FINAL;

    /**
     * Canonical block/identity hash recorded at write time (EVM block hash, Starknet block
     * hash). Null for chains/rows where no reorg primitive applies — see V4 migration comment.
     */
    @Column(name = "block_hash", length = 128)
    private String blockHash;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public UUID getDeploymentId() { return deploymentId; }
    public void setDeploymentId(UUID deploymentId) { this.deploymentId = deploymentId; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public String getContractAddress() { return contractAddress; }
    public void setContractAddress(String contractAddress) { this.contractAddress = contractAddress; }

    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }

    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }

    public BigDecimal getTokenId() { return tokenId; }
    public void setTokenId(BigDecimal tokenId) { this.tokenId = tokenId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }

    public Long getBlockNumber() { return blockNumber; }
    public void setBlockNumber(Long blockNumber) { this.blockNumber = blockNumber; }

    public Integer getLogIndex() { return logIndex; }
    public void setLogIndex(Integer logIndex) { this.logIndex = logIndex; }

    public Long getSlot() { return slot; }
    public void setSlot(Long slot) { this.slot = slot; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public String getExplorerTxUrl() { return explorerTxUrl; }
    public void setExplorerTxUrl(String explorerTxUrl) { this.explorerTxUrl = explorerTxUrl; }

    public Map<String, Object> getRawData() { return rawData; }
    public void setRawData(Map<String, Object> rawData) { this.rawData = rawData; }

    public FinalityStatus getFinalityStatus() { return finalityStatus; }
    public void setFinalityStatus(FinalityStatus finalityStatus) { this.finalityStatus = finalityStatus; }

    public String getBlockHash() { return blockHash; }
    public void setBlockHash(String blockHash) { this.blockHash = blockHash; }
}
