package de.makibytes.registerwerk.dora.api;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** DORA Art. 28 ICT third-party provider register entry. */
@Entity
@Table(name = "third_party_provider")
public class ThirdPartyProvider {

    public enum Criticality { STANDARD, IMPORTANT, CRITICAL }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Criticality criticality = Criticality.STANDARD;

    private String lei;

    @Column(length = 2)
    private String country;

    @Column(name = "contract_start")
    private LocalDate contractStart;

    @Column(name = "contract_end")
    private LocalDate contractEnd;

    @Column(name = "sub_outsourcing")
    private boolean subOutsourcing = false;

    @Column(name = "sub_outsourcing_details", columnDefinition = "TEXT")
    private String subOutsourcingDetails;

    @Column(name = "primary_contact")
    private String primaryContact;

    @Column(name = "sla_availability_pct", precision = 5, scale = 2)
    private BigDecimal slaAvailabilityPct;

    @Column(name = "rto_hours")
    private Integer rtoHours;

    @Column(name = "rpo_hours")
    private Integer rpoHours;

    @Column(name = "notified_authority")
    private boolean notifiedAuthority = false;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getCategory() { return category; }
    public void setCategory(String c) { this.category = c; }
    public Criticality getCriticality() { return criticality; }
    public void setCriticality(Criticality c) { this.criticality = c; }
    public String getLei() { return lei; }
    public void setLei(String l) { this.lei = l; }
    public String getCountry() { return country; }
    public void setCountry(String c) { this.country = c; }
    public LocalDate getContractStart() { return contractStart; }
    public void setContractStart(LocalDate d) { this.contractStart = d; }
    public LocalDate getContractEnd() { return contractEnd; }
    public void setContractEnd(LocalDate d) { this.contractEnd = d; }
    public boolean isSubOutsourcing() { return subOutsourcing; }
    public void setSubOutsourcing(boolean b) { this.subOutsourcing = b; }
    public String getSubOutsourcingDetails() { return subOutsourcingDetails; }
    public void setSubOutsourcingDetails(String s) { this.subOutsourcingDetails = s; }
    public String getPrimaryContact() { return primaryContact; }
    public void setPrimaryContact(String s) { this.primaryContact = s; }
    public BigDecimal getSlaAvailabilityPct() { return slaAvailabilityPct; }
    public void setSlaAvailabilityPct(BigDecimal v) { this.slaAvailabilityPct = v; }
    public Integer getRtoHours() { return rtoHours; }
    public void setRtoHours(Integer v) { this.rtoHours = v; }
    public Integer getRpoHours() { return rpoHours; }
    public void setRpoHours(Integer v) { this.rpoHours = v; }
    public boolean isNotifiedAuthority() { return notifiedAuthority; }
    public void setNotifiedAuthority(boolean b) { this.notifiedAuthority = b; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(Instant t) { this.notifiedAt = t; }
    public String getNotes() { return notes; }
    public void setNotes(String s) { this.notes = s; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
