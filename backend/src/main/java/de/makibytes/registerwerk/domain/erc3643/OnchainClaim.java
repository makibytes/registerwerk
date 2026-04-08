package de.makibytes.registerwerk.domain.erc3643;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a claim issued to an ONCHAINID identity contract.
 * Claims attest to a specific property (e.g. KYC, AML) and are signed by a trusted issuer.
 */
@Entity
@Table(name = "onchain_claim")
public class OnchainClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The ONCHAINID identity this claim belongs to. */
    @Column(name = "onchain_identity_id", nullable = false)
    private UUID onchainIdentityId;

    /** ERC-3643 claim topic number (e.g. 1 = KYC, 2 = AML, 3 = ACCREDITATION). */
    @Column(name = "topic", nullable = false)
    private long topic;

    /** Human-readable label for the topic (e.g. "KYC"). */
    @Column(name = "topic_label", length = 100)
    private String topicLabel;

    /** Address of the trusted issuer that signed this claim. */
    @Column(name = "issuer_address", nullable = false, length = 66)
    private String issuerAddress;

    /** On-chain claim identifier (keccak256 of issuer + topic). */
    @Column(name = "claim_id", length = 66)
    private String claimId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    /** Optional expiry; null means the claim does not expire. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Set when the claim is revoked; null means currently valid. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Transaction hash of the addClaim call. */
    @Column(name = "tx_hash", length = 66)
    private String txHash;

    /**
     * Hex-encoded ABI-encoded claim data bytes (topic + issuer + data payload).
     * Stored so the registry can reconstruct or verify the claim off-chain.
     */
    @Column(name = "claim_data", columnDefinition = "TEXT")
    private String claimData;

    /**
     * Hex-encoded ECDSA signature over the claim hash, produced by the trusted issuer.
     * Required to submit the claim to ONCHAINID.addClaim() and to verify it off-chain.
     */
    @Column(name = "claim_signature", columnDefinition = "TEXT")
    private String claimSignature;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getOnchainIdentityId() { return onchainIdentityId; }
    public void setOnchainIdentityId(UUID onchainIdentityId) { this.onchainIdentityId = onchainIdentityId; }

    public long getTopic() { return topic; }
    public void setTopic(long topic) { this.topic = topic; }

    public String getTopicLabel() { return topicLabel; }
    public void setTopicLabel(String topicLabel) { this.topicLabel = topicLabel; }

    public String getIssuerAddress() { return issuerAddress; }
    public void setIssuerAddress(String issuerAddress) { this.issuerAddress = issuerAddress; }

    public String getClaimId() { return claimId; }
    public void setClaimId(String claimId) { this.claimId = claimId; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }

    public String getClaimData() { return claimData; }
    public void setClaimData(String claimData) { this.claimData = claimData; }

    public String getClaimSignature() { return claimSignature; }
    public void setClaimSignature(String claimSignature) { this.claimSignature = claimSignature; }
}
