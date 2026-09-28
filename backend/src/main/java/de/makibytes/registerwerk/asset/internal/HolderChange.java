package de.makibytes.registerwerk.asset.internal;

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

/** T3-13: one row per executed register-entry change — instruction, actor, approver, before/after. */
@Entity
@Table(name = "asset_holder_change")
public class HolderChange {

    public enum ChangeType { CREATED, ATTRIBUTES_CHANGED, RIGHTS_CHANGED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "holder_id", nullable = false)
    private UUID holderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 30)
    private ChangeType changeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "instructing_party", nullable = false, length = 40)
    private InstructingParty instructingParty;

    @Column(name = "instruction_reference", nullable = false)
    private String instructionReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private Map<String, Object> beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private Map<String, Object> afterState;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_role", length = 40)
    private String actorRole;

    @Column(name = "approver_id")
    private UUID approverId;

    @Column(name = "change_request_id")
    private UUID changeRequestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }
    public UUID getHolderId() { return holderId; }
    public void setHolderId(UUID holderId) { this.holderId = holderId; }
    public ChangeType getChangeType() { return changeType; }
    public void setChangeType(ChangeType changeType) { this.changeType = changeType; }
    public InstructingParty getInstructingParty() { return instructingParty; }
    public void setInstructingParty(InstructingParty instructingParty) { this.instructingParty = instructingParty; }
    public String getInstructionReference() { return instructionReference; }
    public void setInstructionReference(String instructionReference) { this.instructionReference = instructionReference; }
    public Map<String, Object> getBeforeState() { return beforeState; }
    public void setBeforeState(Map<String, Object> beforeState) { this.beforeState = beforeState; }
    public Map<String, Object> getAfterState() { return afterState; }
    public void setAfterState(Map<String, Object> afterState) { this.afterState = afterState; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }
    public UUID getApproverId() { return approverId; }
    public void setApproverId(UUID approverId) { this.approverId = approverId; }
    public UUID getChangeRequestId() { return changeRequestId; }
    public void setChangeRequestId(UUID changeRequestId) { this.changeRequestId = changeRequestId; }
    public Instant getCreatedAt() { return createdAt; }
}
