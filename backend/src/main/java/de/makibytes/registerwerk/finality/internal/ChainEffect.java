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

    @Column(name = "source_event_key", nullable = false, length = 300)
    private String sourceEventKey;

    @Column(name = "module_name", nullable = false, length = 40)
    private String moduleName;

    @Column(name = "effect_type", nullable = false, length = 60)
    private String effectType;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

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

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt = Instant.now();

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "compensated_at")
    private Instant compensatedAt;

    @Column(name = "acknowledged_by")
    private UUID acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

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

    String getLastError() { return lastError; }
    void setLastError(String lastError) { this.lastError = lastError; }

    Instant getRecordedAt() { return recordedAt; }
    void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }

    Instant getSettledAt() { return settledAt; }
    void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }

    Instant getCompensatedAt() { return compensatedAt; }
    void setCompensatedAt(Instant compensatedAt) { this.compensatedAt = compensatedAt; }

    UUID getAcknowledgedBy() { return acknowledgedBy; }
    void setAcknowledgedBy(UUID acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }

    Instant getAcknowledgedAt() { return acknowledgedAt; }
    void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
}
