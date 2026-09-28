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

/**
 * T3-13: an issuer's request to enter or change a register entry. The issuer can only ask; the
 * operator executes it (4-eyes) against the instruction the issuer relays, or rejects it.
 */
@Entity
@Table(name = "holder_change_request")
public class HolderChangeRequest {

    public enum RequestType { ADD_HOLDER, UPDATE_ATTRIBUTES }

    public enum Status { REQUESTED, EXECUTED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 30)
    private RequestType requestType;

    @Column(name = "holder_id")
    private UUID holderId;

    /** ADD_HOLDER: investorId, walletAddress, nominalAmount, singleEntry, isConsumer, attributes.
     *  UPDATE_ATTRIBUTES: the {@link HolderService.AttributeChange} fields. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "instructing_party", nullable = false, length = 40)
    private InstructingParty instructingParty;

    @Column(name = "instruction_reference", nullable = false)
    private String instructionReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.REQUESTED;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "requested_by_role", length = 40)
    private String requestedByRole;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "resulting_change_id")
    private UUID resultingChangeId;

    public UUID getId() { return id; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }
    public RequestType getRequestType() { return requestType; }
    public void setRequestType(RequestType requestType) { this.requestType = requestType; }
    public UUID getHolderId() { return holderId; }
    public void setHolderId(UUID holderId) { this.holderId = holderId; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public InstructingParty getInstructingParty() { return instructingParty; }
    public void setInstructingParty(InstructingParty instructingParty) { this.instructingParty = instructingParty; }
    public String getInstructionReference() { return instructionReference; }
    public void setInstructionReference(String instructionReference) { this.instructionReference = instructionReference; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public UUID getRequestedBy() { return requestedBy; }
    public void setRequestedBy(UUID requestedBy) { this.requestedBy = requestedBy; }
    public String getRequestedByRole() { return requestedByRole; }
    public void setRequestedByRole(String requestedByRole) { this.requestedByRole = requestedByRole; }
    public Instant getRequestedAt() { return requestedAt; }
    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public UUID getResultingChangeId() { return resultingChangeId; }
    public void setResultingChangeId(UUID resultingChangeId) { this.resultingChangeId = resultingChangeId; }
}
