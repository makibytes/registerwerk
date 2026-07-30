package de.makibytes.registerwerk.customer.api;

import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.KycStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "legal_entity")
public class LegalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_number", nullable = false, unique = true, length = 30)
    @NotBlank
    private String entityNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull
    private EntityType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @NotNull
    private EntityStatus status = EntityStatus.PENDING_ONBOARDING;

    @Column(name = "current_name", nullable = false, length = 500)
    @NotBlank
    @Size(max = 500)
    private String currentName;

    @Column(name = "lei_code", length = 20)
    private String leiCode;

    @Column(name = "registration_number", length = 100)
    private String registrationNumber;

    @Column(name = "registration_country", length = 2)
    private String registrationCountry;

    @Column(name = "idp_issuer_url", length = 500)
    private String idpIssuerUrl;

    @Column(name = "idp_client_id", length = 255)
    private String idpClientId;

    /**
     * @deprecated Never populated since V19. Inbound B2B federation is configured tenant-to-tenant
     *     in the Entra portal; Registerwerk never runs an authorization-code flow against a
     *     customer's tenant, so it has no use for their client secret — and this column stored it
     *     in plaintext. The column remains because V1 is shipped and migrations are not edited.
     */
    @Deprecated
    @Column(name = "idp_client_secret", length = 500)
    private String idpClientSecret;

    /**
     * Whether this customer's users are invited into the operator's own tenant (and their MFA is
     * therefore manageable from here) or federated to the customer's own tenant. One of
     * {@code WORKFORCE_MEMBER}, {@code WORKFORCE_GUEST}, {@code FEDERATED}, enforced by a CHECK
     * constraint in V19.
     *
     * <p>Deliberately a String rather than the {@code entra} module's {@code EntraIdentityModel}:
     * {@code entra} depends on {@code customer.api}, so typing it here would close a module cycle.
     * The {@code entra} module parses it — a small cost to keep {@code customer} ignorant of the
     * identity provider.
     *
     * <p>Records operator <em>intent</em>. Once a user has signed in, the {@code tid} on their
     * token is ground truth and wins.
     */
    @Column(name = "identity_model", length = 20, nullable = false)
    private String identityModel = "WORKFORCE_GUEST";

    /** The federated customer's Entra tenant id, derived from {@link #idpIssuerUrl}. */
    @Column(name = "idp_tenant_id")
    private UUID idpTenantId;

    /**
     * Whether MFA performed in the customer's home tenant is accepted here (Entra cross-tenant
     * access settings). Operator-controlled only — a customer asserting "trust my MFA" about
     * themselves would be a privilege-escalation path.
     */
    @Column(name = "idp_mfa_trusted", nullable = false)
    private boolean idpMfaTrusted = false;

    @Column(name = "incorporation_date")
    private LocalDate incorporationDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 20)
    @NotNull
    private KycStatus kycStatus = KycStatus.NOT_STARTED;

    @Column(name = "kyc_expiry_date")
    private LocalDate kycExpiryDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "created_by")
    private UUID createdBy;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEntityNumber() { return entityNumber; }
    public void setEntityNumber(String entityNumber) { this.entityNumber = entityNumber; }

    public EntityType getType() { return type; }
    public void setType(EntityType type) { this.type = type; }

    public EntityStatus getStatus() { return status; }
    public void setStatus(EntityStatus status) { this.status = status; }

    public String getCurrentName() { return currentName; }
    public void setCurrentName(String currentName) { this.currentName = currentName; }

    public String getLeiCode() { return leiCode; }
    public void setLeiCode(String leiCode) { this.leiCode = leiCode; }

    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }

    public String getRegistrationCountry() { return registrationCountry; }
    public void setRegistrationCountry(String registrationCountry) { this.registrationCountry = registrationCountry; }

    public String getIdpIssuerUrl() { return idpIssuerUrl; }
    public void setIdpIssuerUrl(String idpIssuerUrl) { this.idpIssuerUrl = idpIssuerUrl; }

    public String getIdpClientId() { return idpClientId; }
    public void setIdpClientId(String idpClientId) { this.idpClientId = idpClientId; }

    /** @deprecated see the field. */
    @Deprecated
    public String getIdpClientSecret() { return idpClientSecret; }
    /** @deprecated see the field — nothing should call this. */
    @Deprecated
    public void setIdpClientSecret(String idpClientSecret) { this.idpClientSecret = idpClientSecret; }

    public String getIdentityModel() { return identityModel; }
    public void setIdentityModel(String identityModel) {
        this.identityModel = identityModel == null || identityModel.isBlank()
                ? "WORKFORCE_GUEST"
                : identityModel;
    }

    public UUID getIdpTenantId() { return idpTenantId; }
    public void setIdpTenantId(UUID idpTenantId) { this.idpTenantId = idpTenantId; }

    public boolean isIdpMfaTrusted() { return idpMfaTrusted; }
    public void setIdpMfaTrusted(boolean idpMfaTrusted) { this.idpMfaTrusted = idpMfaTrusted; }

    public LocalDate getIncorporationDate() { return incorporationDate; }
    public void setIncorporationDate(LocalDate incorporationDate) { this.incorporationDate = incorporationDate; }

    public KycStatus getKycStatus() { return kycStatus; }
    public void setKycStatus(KycStatus kycStatus) { this.kycStatus = kycStatus; }

    public LocalDate getKycExpiryDate() { return kycExpiryDate; }
    public void setKycExpiryDate(LocalDate kycExpiryDate) { this.kycExpiryDate = kycExpiryDate; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
