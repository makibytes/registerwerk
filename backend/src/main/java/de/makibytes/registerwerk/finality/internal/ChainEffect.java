package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.CompensationCategory;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One row per on-chain-caused state change, journalled so it can be undone if the source block
 *  is later orphaned — see {@code ChainEffectRecorder}'s javadoc for the full rationale. */
@Entity
@Table(name = "chain_effect")
class ChainEffect {

    enum Status { ACTIVE, SETTLED, COMPENSATING, COMPENSATED, COMPENSATION_FAILED, IRREVERSIBLE_ESCALATED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    @Column(name = "block_number", nullable = false)
    private long blockNumber;

    @Column(name = "block_hash", length = 128)
    private String blockHash;

    @Column(name = "tx_hash", length = 128)
    private String txHash;

    @Column(name = "log_index")
    private Integer logIndex;

    @Column(name = "source_event_key", nullable = false, length = 512)
    private String sourceEventKey;

    @Column(name = "module_name", nullable = false, length = 40)
    private String moduleName;

    @Column(name = "effect_type", nullable = false, length = 60)
    private String effectType;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    /** The asset this effect is scoped to, when there is one — see {@code ChainEffectDescriptor
     *  #assetId}'s javadoc. Null for effect types that are not asset-scoped. */
    @Column(name = "asset_id")
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private CompensationCategory category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private Map<String, Object> beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private Map<String, Object> afterState;

    @Column(name = "audit_event_id")
    private UUID auditEventId;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.ACTIVE;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    /** Set on every terminal outcome — a real failure reason (Failed/Irreversible) or a benign
     *  "already undone" explanation (NotApplicable) alike. Named for what it always is (an
     *  explanation of how the row was resolved), not what it sometimes isn't (an error) — was
     *  originally {@code last_error}, which mislabeled the NotApplicable case as if something had
     *  gone wrong. */
    @Column(name = "resolution_detail")
    private String resolutionDetail;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt = Instant.now();

    /** Database-assigned insertion order. Unlike {@code recordedAt}, this remains distinct when
     * several effects are journalled in one PostgreSQL transaction (where CURRENT_TIMESTAMP is
     * deliberately stable), so saga compensation can always run in true reverse write order. */
    @Column(name = "journal_sequence", nullable = false, insertable = false, updatable = false)
    private long journalSequence;

    /** When this row was last claimed into {@code COMPENSATING} — lets
     *  {@code ChainEffectRepository#claimForCompensation} reclaim a row whose compensator crashed
     *  mid-run (stuck in {@code COMPENSATING} forever otherwise) after a timeout, instead of only
     *  ever reclaiming {@code ACTIVE}/{@code COMPENSATION_FAILED}. Null until first claimed. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "compensated_at")
    private Instant compensatedAt;

    @Column(name = "acknowledged_by")
    private UUID acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    /** The mandatory, audited justification an admin gave for acknowledging (unblocking) this
     *  effect — same break-glass convention as {@code finality_policy_override.reason}. */
    @Column(name = "acknowledge_reason")
    private String acknowledgeReason;

    UUID getId() { return id; }

    UUID getChainConfigId() { return chainConfigId; }
    void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    long getBlockNumber() { return blockNumber; }
    void setBlockNumber(long blockNumber) { this.blockNumber = blockNumber; }

    String getBlockHash() { return blockHash; }
    void setBlockHash(String blockHash) { this.blockHash = blockHash; }

    String getTxHash() { return txHash; }
    void setTxHash(String txHash) { this.txHash = txHash; }

    Integer getLogIndex() { return logIndex; }
    void setLogIndex(Integer logIndex) { this.logIndex = logIndex; }

    String getSourceEventKey() { return sourceEventKey; }
    void setSourceEventKey(String sourceEventKey) { this.sourceEventKey = sourceEventKey; }

    String getModuleName() { return moduleName; }
    void setModuleName(String moduleName) { this.moduleName = moduleName; }

    String getEffectType() { return effectType; }
    void setEffectType(String effectType) { this.effectType = effectType; }

    String getEntityType() { return entityType; }
    void setEntityType(String entityType) { this.entityType = entityType; }

    UUID getEntityId() { return entityId; }
    void setEntityId(UUID entityId) { this.entityId = entityId; }

    UUID getAssetId() { return assetId; }
    void setAssetId(UUID assetId) { this.assetId = assetId; }

    CompensationCategory getCategory() { return category; }
    void setCategory(CompensationCategory category) { this.category = category; }

    Map<String, Object> getBeforeState() { return beforeState; }
    void setBeforeState(Map<String, Object> beforeState) { this.beforeState = beforeState; }

    Map<String, Object> getAfterState() { return afterState; }
    void setAfterState(Map<String, Object> afterState) { this.afterState = afterState; }

    UUID getAuditEventId() { return auditEventId; }
    void setAuditEventId(UUID auditEventId) { this.auditEventId = auditEventId; }

    UUID getCorrelationId() { return correlationId; }
    void setCorrelationId(UUID correlationId) { this.correlationId = correlationId; }

    Status getStatus() { return status; }
    void setStatus(Status status) { this.status = status; }

    int getAttemptCount() { return attemptCount; }
    void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    String getResolutionDetail() { return resolutionDetail; }
    void setResolutionDetail(String resolutionDetail) { this.resolutionDetail = resolutionDetail; }

    Instant getRecordedAt() { return recordedAt; }
    void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }

    long getJournalSequence() { return journalSequence; }

    Instant getClaimedAt() { return claimedAt; }
    void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }

    Instant getSettledAt() { return settledAt; }
    void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }

    Instant getCompensatedAt() { return compensatedAt; }
    void setCompensatedAt(Instant compensatedAt) { this.compensatedAt = compensatedAt; }

    UUID getAcknowledgedBy() { return acknowledgedBy; }
    void setAcknowledgedBy(UUID acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }

    Instant getAcknowledgedAt() { return acknowledgedAt; }
    void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }

    String getAcknowledgeReason() { return acknowledgeReason; }
    void setAcknowledgeReason(String acknowledgeReason) { this.acknowledgeReason = acknowledgeReason; }
}
