package de.makibytes.registerwerk.domain.erc3643;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Represents a trusted claim issuer registered in a T-REX suite's TrustedIssuersRegistry.
 * The issuer is authorised to sign claims for the listed claim topics.
 */
@Entity
@Table(name = "erc3643_trusted_issuer")
public class Erc3643TrustedIssuer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The ERC-3643 suite this issuer belongs to. */
    @Column(name = "suite_id", nullable = false)
    private UUID suiteId;

    /** On-chain address of the trusted issuer (EOA or contract). */
    @Column(name = "issuer_address", nullable = false, length = 66)
    private String issuerAddress;

    /**
     * The claim topic numbers this issuer is authorised to sign, stored as a PostgreSQL long[].
     * Maps to {@code bigint[]} in the database via the native array JDBC type.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "claim_topics", columnDefinition = "bigint[]")
    private List<Long> claimTopics;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    /**
     * Optional link to a {@code LegalEntity} in this registry that acts as the claim issuer.
     * Null for external KYC providers (e.g. Onfido, Synaps); non-null when the registry
     * operator itself issues claims on behalf of one of its own entities.
     */
    @Column(name = "legal_entity_id")
    private UUID legalEntityId;

    /** Set when the issuer is removed from the registry; null means currently trusted. */
    @Column(name = "removed_at")
    private Instant removedAt;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getSuiteId() { return suiteId; }
    public void setSuiteId(UUID suiteId) { this.suiteId = suiteId; }

    public String getIssuerAddress() { return issuerAddress; }
    public void setIssuerAddress(String issuerAddress) { this.issuerAddress = issuerAddress; }

    public List<Long> getClaimTopics() { return claimTopics; }
    public void setClaimTopics(List<Long> claimTopics) { this.claimTopics = claimTopics; }

    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }

    public UUID getLegalEntityId() { return legalEntityId; }
    public void setLegalEntityId(UUID legalEntityId) { this.legalEntityId = legalEntityId; }

    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }
}
