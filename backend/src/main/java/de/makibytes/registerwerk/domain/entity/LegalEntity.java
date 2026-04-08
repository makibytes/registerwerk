package de.makibytes.registerwerk.domain.entity;

import de.makibytes.registerwerk.domain.enums.EntityStatus;
import de.makibytes.registerwerk.domain.enums.EntityType;
import de.makibytes.registerwerk.domain.enums.KycStatus;
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
