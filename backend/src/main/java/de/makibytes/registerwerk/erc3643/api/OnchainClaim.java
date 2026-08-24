package de.makibytes.registerwerk.erc3643.api;

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

    /** FK to {@code chain_config} — set only once {@link #confirmed} is true. */
    @Column(name = "chain_config_id")
    private UUID chainConfigId;

    @Column(name = "block_number")
    private Long blockNumber;

    @Column(name = "block_hash", length = 128)
    private String blockHash;

    /** True once {@link de.makibytes.registerwerk.erc3643.internal.Erc3643ClaimConfirmationListener}
     *  has confirmed {@link #txHash} SUCCESS. {@code getActiveClaims} only counts claims where this
     *  is {@code true} — a freshly-submitted claim does not unblock compliance checks until its
     *  {@code addClaim} tx reaches FINALIZED. Claims issued before this column existed were
     *  grandfathered in as {@code true} by the migration that added it (see V11). */
    @Column(name = "confirmed", nullable = false)
    private boolean confirmed = false;

    /** Transaction hash of a submitted {@code removeClaim} call. Non-null is also the fail-closed
     *  revocation-intent marker, so the claim is excluded from active compliance claims before
     *  finality and after a confirming block is retracted. {@link #revokedAt} is only set once
     *  {@code Erc3643ClaimConfirmationListener} confirms this tx; only a confirmed failed receipt
     *  clears the hash and restores the claim. */
    @Column(name = "revocation_tx_hash", length = 80)
    private String revocationTxHash;

    @Column(name = "revocation_chain_config_id")
    private UUID revocationChainConfigId;

    @Column(name = "revocation_block_number")
    private Long revocationBlockNumber;

    @Column(name = "revocation_block_hash", length = 128)
    private String revocationBlockHash;

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

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public Long getBlockNumber() { return blockNumber; }
    public void setBlockNumber(Long blockNumber) { this.blockNumber = blockNumber; }

    public String getBlockHash() { return blockHash; }
    public void setBlockHash(String blockHash) { this.blockHash = blockHash; }

    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }

    public String getRevocationTxHash() { return revocationTxHash; }
    public void setRevocationTxHash(String revocationTxHash) { this.revocationTxHash = revocationTxHash; }

    public UUID getRevocationChainConfigId() { return revocationChainConfigId; }
    public void setRevocationChainConfigId(UUID revocationChainConfigId) { this.revocationChainConfigId = revocationChainConfigId; }

    public Long getRevocationBlockNumber() { return revocationBlockNumber; }
    public void setRevocationBlockNumber(Long revocationBlockNumber) { this.revocationBlockNumber = revocationBlockNumber; }

    public String getRevocationBlockHash() { return revocationBlockHash; }
    public void setRevocationBlockHash(String revocationBlockHash) { this.revocationBlockHash = revocationBlockHash; }

    public String getClaimData() { return claimData; }
    public void setClaimData(String claimData) { this.claimData = claimData; }

    public String getClaimSignature() { return claimSignature; }
    public void setClaimSignature(String claimSignature) { this.claimSignature = claimSignature; }
}
