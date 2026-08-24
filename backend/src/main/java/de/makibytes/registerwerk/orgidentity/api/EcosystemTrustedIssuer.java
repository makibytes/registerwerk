package de.makibytes.registerwerk.orgidentity.api;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors a claim issuer trusted ecosystem-wide (EcosystemTrustedIssuersRegistry).
 * Claims on org ONCHAINIDs only count for {@code hasClaimTopic} when signed by an
 * issuer trusted for that topic.
 */
@Entity
@Table(name = "ecosystem_trusted_issuer")
public class EcosystemTrustedIssuer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    @Column(name = "issuer_address", nullable = false, length = 66)
    private String issuerAddress;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "claim_topics", columnDefinition = "bigint[]", nullable = false)
    private List<Long> claimTopics;

    /** Optional link to a legal entity in this registry acting as the issuer. */
    @Column(name = "legal_entity_id")
    private UUID legalEntityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TrustedIssuerStatus status = TrustedIssuerStatus.PENDING;

    @Column(name = "added_tx", length = 66)
    private String addedTx;

    @Column(name = "added_block_number")
    private Long addedBlockNumber;

    @Column(name = "added_block_hash", length = 128)
    private String addedBlockHash;

    @Column(name = "removed_tx", length = 66)
    private String removedTx;

    @Column(name = "removed_block_number")
    private Long removedBlockNumber;

    @Column(name = "removed_block_hash", length = 128)
    private String removedBlockHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "dual_control_approver_id")
    private UUID dualControlApproverId;

    @Column(name = "dual_control_approved_at")
    private Instant dualControlApprovedAt;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public String getIssuerAddress() { return issuerAddress; }
    public void setIssuerAddress(String issuerAddress) { this.issuerAddress = issuerAddress; }

    public List<Long> getClaimTopics() { return claimTopics; }
    public void setClaimTopics(List<Long> claimTopics) { this.claimTopics = claimTopics; }

    public UUID getLegalEntityId() { return legalEntityId; }
    public void setLegalEntityId(UUID legalEntityId) { this.legalEntityId = legalEntityId; }

    public TrustedIssuerStatus getStatus() { return status; }
    public void setStatus(TrustedIssuerStatus status) { this.status = status; }

    public String getAddedTx() { return addedTx; }
    public void setAddedTx(String addedTx) { this.addedTx = addedTx; }

    public Long getAddedBlockNumber() { return addedBlockNumber; }
    public void setAddedBlockNumber(Long addedBlockNumber) { this.addedBlockNumber = addedBlockNumber; }

    public String getAddedBlockHash() { return addedBlockHash; }
    public void setAddedBlockHash(String addedBlockHash) { this.addedBlockHash = addedBlockHash; }

    public String getRemovedTx() { return removedTx; }
    public void setRemovedTx(String removedTx) { this.removedTx = removedTx; }

    public Long getRemovedBlockNumber() { return removedBlockNumber; }
    public void setRemovedBlockNumber(Long removedBlockNumber) { this.removedBlockNumber = removedBlockNumber; }

    public String getRemovedBlockHash() { return removedBlockHash; }
    public void setRemovedBlockHash(String removedBlockHash) { this.removedBlockHash = removedBlockHash; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }

    public UUID getDualControlApproverId() { return dualControlApproverId; }
    public void setDualControlApproverId(UUID dualControlApproverId) { this.dualControlApproverId = dualControlApproverId; }

    public Instant getDualControlApprovedAt() { return dualControlApprovedAt; }
    public void setDualControlApprovedAt(Instant dualControlApprovedAt) { this.dualControlApprovedAt = dualControlApprovedAt; }
}
