package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.CaspAuthorizationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Registry entry for the MiCA authorization status of a counterparty CASP. */
@Entity
@Table(name = "casp_authorization")
public class CaspAuthorization {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "vasp_did", nullable = false, unique = true)
    private String vaspDid;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    private String lei;

    @Column(name = "home_member_state", length = 2)
    private String homeMemberState;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CaspAuthorizationStatus status;

    @Column(name = "authorization_id")
    private String authorizationId;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    /** Where the status was verified, e.g. "ESMA register, checked 2026-06-10". */
    private String source;

    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getVaspDid() { return vaspDid; }
    public void setVaspDid(String vaspDid) { this.vaspDid = vaspDid; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }
    public String getLei() { return lei; }
    public void setLei(String lei) { this.lei = lei; }
    public String getHomeMemberState() { return homeMemberState; }
    public void setHomeMemberState(String homeMemberState) { this.homeMemberState = homeMemberState; }
    public CaspAuthorizationStatus getStatus() { return status; }
    public void setStatus(CaspAuthorizationStatus status) { this.status = status; }
    public String getAuthorizationId() { return authorizationId; }
    public void setAuthorizationId(String authorizationId) { this.authorizationId = authorizationId; }
    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
