package de.makibytes.registerwerk.domain.endpoint;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Named addressbook entry mapping a blockchain address to a human-readable label.
 *
 * <p>Scoped to either the registry operator (ownerType=OPERATOR, ownerId=null)
 * or a specific legal entity (ownerType=ENTITY, ownerId=entity UUID).
 */
@Entity
@Table(name = "address_endpoint")
public class AddressEndpoint {

    public enum OwnerType { OPERATOR, ENTITY }
    public enum AddressType { WALLET, CONTRACT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 10)
    @NotNull
    private OwnerType ownerType;

    /** Null when ownerType is OPERATOR. */
    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false, length = 66)
    @NotBlank
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "address_type", nullable = false, length = 10)
    @NotNull
    private AddressType addressType;

    @Column(nullable = false, length = 200)
    @NotBlank
    @Size(max = 200)
    private String name;

    @Column(length = 500)
    @Size(max = 500)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 10)
    private RiskLevel riskLevel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }

    public OwnerType getOwnerType() { return ownerType; }
    public void setOwnerType(OwnerType ownerType) { this.ownerType = ownerType; }

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public AddressType getAddressType() { return addressType; }
    public void setAddressType(AddressType addressType) { this.addressType = addressType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
