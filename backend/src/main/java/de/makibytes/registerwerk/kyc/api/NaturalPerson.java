package de.makibytes.registerwerk.kyc.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents a natural person (individual) for KYC / beneficial-owner purposes.
 * Required by GwG §11 and EU 2024/1624 AMLR.
 * WARNING: the current mapped PII columns are ordinary plaintext database fields. Production
 * use requires an approved encryption/key-lifecycle design and a tested data migration.
 */
@Entity
@Table(name = "natural_person")
public class NaturalPerson {

    public enum PepStatus {
        UNKNOWN, NOT_PEP, DOMESTIC_PEP, FOREIGN_PEP,
        INTERNATIONAL_PEP, PEP_FAMILY, PEP_ASSOCIATE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "given_name", nullable = false)
    private String givenName;

    @Column(name = "family_name", nullable = false)
    private String familyName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "nationality", length = 2)
    private String nationality;

    @Column(name = "country_of_residence", length = 2)
    private String countryOfResidence;

    @Column(name = "tax_id")
    private String taxId;

    @Column(name = "tax_id_country", length = 2)
    private String taxIdCountry;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(name = "city")
    private String city;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(name = "country", length = 2)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(name = "pep_status", nullable = false, length = 30)
    private PepStatus pepStatus = PepStatus.UNKNOWN;

    @Column(name = "pep_status_updated_at")
    private Instant pepStatusUpdatedAt;

    @Column(name = "redacted", nullable = false)
    private boolean redacted = false;

    @Column(name = "redacted_at")
    private Instant redactedAt;

    @Column(name = "redacted_by")
    private UUID redactedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public String getGivenName() { return givenName; }
    public void setGivenName(String v) { this.givenName = v; }
    public String getFamilyName() { return familyName; }
    public void setFamilyName(String v) { this.familyName = v; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate v) { this.dateOfBirth = v; }
    public String getNationality() { return nationality; }
    public void setNationality(String v) { this.nationality = v; }
    public String getCountryOfResidence() { return countryOfResidence; }
    public void setCountryOfResidence(String v) { this.countryOfResidence = v; }
    public String getTaxId() { return taxId; }
    public void setTaxId(String v) { this.taxId = v; }
    public String getTaxIdCountry() { return taxIdCountry; }
    public void setTaxIdCountry(String v) { this.taxIdCountry = v; }
    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String v) { this.addressLine1 = v; }
    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String v) { this.addressLine2 = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String v) { this.postalCode = v; }
    public String getCountry() { return country; }
    public void setCountry(String v) { this.country = v; }
    public PepStatus getPepStatus() { return pepStatus; }
    public void setPepStatus(PepStatus v) { this.pepStatus = v; pepStatusUpdatedAt = Instant.now(); }
    public Instant getPepStatusUpdatedAt() { return pepStatusUpdatedAt; }
    public boolean isRedacted() { return redacted; }
    public void redact(UUID byUserId) {
        this.redacted = true;
        this.redactedAt = Instant.now();
        this.redactedBy = byUserId;
        this.givenName = "[REDACTED]";
        this.familyName = "[REDACTED]";
        this.dateOfBirth = null;
        this.taxId = null;
        this.addressLine1 = null;
        this.addressLine2 = null;
        this.city = null;
        this.postalCode = null;
    }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
