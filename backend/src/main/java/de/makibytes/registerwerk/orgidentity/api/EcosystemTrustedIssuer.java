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
    private MemberWalletStatus status = MemberWalletStatus.PENDING;

    @Column(name = "added_tx", length = 66)
    private String addedTx;

    @Column(name = "removed_tx", length = 66)
    private String removedTx;

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

    public MemberWalletStatus getStatus() { return status; }
    public void setStatus(MemberWalletStatus status) { this.status = status; }

    public String getAddedTx() { return addedTx; }
    public void setAddedTx(String addedTx) { this.addedTx = addedTx; }

    public String getRemovedTx() { return removedTx; }
    public void setRemovedTx(String removedTx) { this.removedTx = removedTx; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }

    public UUID getDualControlApproverId() { return dualControlApproverId; }
    public void setDualControlApproverId(UUID dualControlApproverId) { this.dualControlApproverId = dualControlApproverId; }

    public Instant getDualControlApprovedAt() { return dualControlApprovedAt; }
    public void setDualControlApprovedAt(Instant dualControlApprovedAt) { this.dualControlApprovedAt = dualControlApprovedAt; }
}
