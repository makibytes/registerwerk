package de.makibytes.registerwerk.asset.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/**
 * One signed {@code EwpgPaymaster} voucher. {@link #maxCostWei} is the UserOperation's worst-case
 * prefund — what the paymaster reserves on chain — and is what counts against the policy's
 * {@code monthlyCapEth}, so the cap holds even before any op settles.
 */
@Entity
@Table(name = "gas_sponsorship_voucher")
public class GasSponsorshipVoucher {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "asset_deployment_id", nullable = false)
    private UUID assetDeploymentId;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(nullable = false, length = 42)
    private String sender;

    @Column(name = "chain_id", nullable = false)
    private Long chainId;

    @Column(name = "user_op_nonce", nullable = false, precision = 78, scale = 0)
    private BigInteger userOpNonce;

    @Column(name = "max_cost_wei", nullable = false, precision = 78, scale = 0)
    private BigInteger maxCostWei;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private UUID createdBy;

    public UUID getId() { return id; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public UUID getAssetDeploymentId() { return assetDeploymentId; }
    public void setAssetDeploymentId(UUID assetDeploymentId) { this.assetDeploymentId = assetDeploymentId; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public Long getChainId() { return chainId; }
    public void setChainId(Long chainId) { this.chainId = chainId; }
    public BigInteger getUserOpNonce() { return userOpNonce; }
    public void setUserOpNonce(BigInteger userOpNonce) { this.userOpNonce = userOpNonce; }
    public BigInteger getMaxCostWei() { return maxCostWei; }
    public void setMaxCostWei(BigInteger maxCostWei) { this.maxCostWei = maxCostWei; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
