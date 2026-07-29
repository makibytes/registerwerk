package de.makibytes.registerwerk.orgidentity.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors an organization registered in the onchain OrgRegistry. The org is keyed by
 * its ONCHAINID address ({@code orgAddress}); one registration per legal entity per chain.
 */
@Entity
@Table(
    name = "org_registration",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_org_registration_entity_chain",
        columnNames = {"legal_entity_id", "chain_config_id"}
    )
)
public class OrgRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "legal_entity_id", nullable = false)
    private UUID legalEntityId;

    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    /** The org's ONCHAINID address (placeholder while the identity deploy is pending). */
    @Column(name = "org_address", nullable = false, length = 66)
    private String orgAddress;

    /** ISO-3166-1 numeric; kept so a deferred/retried registerOrg broadcast carries it. */
    @Column(name = "country_code")
    private Short countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrgRegistrationStatus status = OrgRegistrationStatus.PENDING;

    @Column(name = "registered_tx", length = 66)
    private String registeredTx;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspended_by")
    private UUID suspendedBy;

    @Column(name = "suspension_reason")
    private String suspensionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getLegalEntityId() { return legalEntityId; }
    public void setLegalEntityId(UUID legalEntityId) { this.legalEntityId = legalEntityId; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public String getOrgAddress() { return orgAddress; }
    public void setOrgAddress(String orgAddress) { this.orgAddress = orgAddress; }

    public Short getCountryCode() { return countryCode; }
    public void setCountryCode(Short countryCode) { this.countryCode = countryCode; }

    public OrgRegistrationStatus getStatus() { return status; }
    public void setStatus(OrgRegistrationStatus status) { this.status = status; }

    public String getRegisteredTx() { return registeredTx; }
    public void setRegisteredTx(String registeredTx) { this.registeredTx = registeredTx; }

    public Instant getSuspendedAt() { return suspendedAt; }
    public void setSuspendedAt(Instant suspendedAt) { this.suspendedAt = suspendedAt; }

    public UUID getSuspendedBy() { return suspendedBy; }
    public void setSuspendedBy(UUID suspendedBy) { this.suspendedBy = suspendedBy; }

    public String getSuspensionReason() { return suspensionReason; }
    public void setSuspensionReason(String suspensionReason) { this.suspensionReason = suspensionReason; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
