package de.makibytes.registerwerk.orgidentity.internal;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks the push of one KYC lapse (expiry or rejection) of a legal entity onto one chain — see
 * {@link KycChainPropagationListener}. One row per entity and chain; a later lapse resets it.
 */
@Entity
@Table(name = "kyc_chain_propagation")
class KycChainPropagation {

    enum Status { PENDING, COMPLETED, FAILED, SUPERSEDED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "legal_entity_id", nullable = false)
    private UUID legalEntityId;

    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    @Column(name = "trigger_reason", nullable = false, length = 32)
    private String triggerReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status;

    @Column(name = "org_action", length = 32)
    private String orgAction;

    @Column(name = "unresolved_claims", nullable = false)
    private int unresolvedClaims;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    UUID getId() { return id; }
    void setId(UUID id) { this.id = id; }
    UUID getLegalEntityId() { return legalEntityId; }
    void setLegalEntityId(UUID legalEntityId) { this.legalEntityId = legalEntityId; }
    UUID getChainConfigId() { return chainConfigId; }
    void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }
    String getTriggerReason() { return triggerReason; }
    void setTriggerReason(String triggerReason) { this.triggerReason = triggerReason; }
    Status getStatus() { return status; }
    void setStatus(Status status) { this.status = status; }
    String getOrgAction() { return orgAction; }
    void setOrgAction(String orgAction) { this.orgAction = orgAction; }
    int getUnresolvedClaims() { return unresolvedClaims; }
    void setUnresolvedClaims(int unresolvedClaims) { this.unresolvedClaims = unresolvedClaims; }
    int getAttempts() { return attempts; }
    void setAttempts(int attempts) { this.attempts = attempts; }
    String getLastError() { return lastError; }
    void setLastError(String lastError) { this.lastError = lastError; }
    Instant getUpdatedAt() { return updatedAt; }
    void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    Instant getCompletedAt() { return completedAt; }
    void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
