package de.makibytes.registerwerk.erc3643.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents the full set of T-REX smart contracts deployed for a given asset deployment.
 * One suite is created per ERC-3643 asset deployment and tracks all six contract addresses.
 */
@Entity
@Table(name = "erc3643_suite")
public class Erc3643Suite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** References the {@code asset_deployment} row this suite belongs to. */
    @Column(name = "asset_deployment_id", nullable = false, unique = true)
    private UUID assetDeploymentId;

    /** Address of the deployed ERC-3643 token contract. */
    @Column(name = "token_address", nullable = false, length = 66)
    private String tokenAddress;

    /** Address of the deployed IdentityRegistry contract. */
    @Column(name = "identity_registry_address", nullable = false, length = 66)
    private String identityRegistryAddress;

    /** Address of the deployed IdentityRegistryStorage contract. */
    @Column(name = "identity_registry_storage", nullable = false, length = 66)
    private String identityRegistryStorage;

    /** Address of the deployed ModularCompliance contract. */
    @Column(name = "compliance_address", nullable = false, length = 66)
    private String complianceAddress;

    /** Address of the deployed ClaimTopicsRegistry contract. */
    @Column(name = "claim_topics_registry", nullable = false, length = 66)
    private String claimTopicsRegistry;

    /** Address of the deployed TrustedIssuersRegistry contract. */
    @Column(name = "trusted_issuers_registry", nullable = false, length = 66)
    private String trustedIssuersRegistry;

    /** Transaction hash of the factory deployment call. */
    @Column(name = "factory_tx_hash", length = 66)
    private String factoryTxHash;

    /** Whether this is a confidential deployment (Fhenix / Inco fhEVM). */
    @Column(name = "is_confidential", nullable = false)
    private boolean isConfidential;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getAssetDeploymentId() { return assetDeploymentId; }
    public void setAssetDeploymentId(UUID assetDeploymentId) { this.assetDeploymentId = assetDeploymentId; }

    public String getTokenAddress() { return tokenAddress; }
    public void setTokenAddress(String tokenAddress) { this.tokenAddress = tokenAddress; }

    public String getIdentityRegistryAddress() { return identityRegistryAddress; }
    public void setIdentityRegistryAddress(String identityRegistryAddress) {
        this.identityRegistryAddress = identityRegistryAddress;
    }

    public String getIdentityRegistryStorage() { return identityRegistryStorage; }
    public void setIdentityRegistryStorage(String identityRegistryStorage) {
        this.identityRegistryStorage = identityRegistryStorage;
    }

    public String getComplianceAddress() { return complianceAddress; }
    public void setComplianceAddress(String complianceAddress) { this.complianceAddress = complianceAddress; }

    public String getClaimTopicsRegistry() { return claimTopicsRegistry; }
    public void setClaimTopicsRegistry(String claimTopicsRegistry) { this.claimTopicsRegistry = claimTopicsRegistry; }

    public String getTrustedIssuersRegistry() { return trustedIssuersRegistry; }
    public void setTrustedIssuersRegistry(String trustedIssuersRegistry) {
        this.trustedIssuersRegistry = trustedIssuersRegistry;
    }

    public String getFactoryTxHash() { return factoryTxHash; }
    public void setFactoryTxHash(String factoryTxHash) { this.factoryTxHash = factoryTxHash; }

    public boolean getIsConfidential() { return isConfidential; }
    public void setIsConfidential(boolean isConfidential) { this.isConfidential = isConfidential; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
