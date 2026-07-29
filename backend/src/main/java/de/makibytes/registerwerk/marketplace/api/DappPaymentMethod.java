package de.makibytes.registerwerk.marketplace.api;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * A payment method a dApp version declares in its manifest: either a reference to an
 * operator-curated payment rail (by code — soft reference, re-checked at approval) or a
 * custom method the dApp implements itself.
 */
@Entity
@Table(name = "dapp_payment_method")
public class DappPaymentMethod {

    public enum MethodType { RAIL, CUSTOM }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method_type", nullable = false, length = 10)
    private MethodType methodType;

    @Column(name = "rail_code", length = 60)
    private String railCode;

    @Column(name = "custom_name", length = 120)
    private String customName;

    @Column(name = "custom_description", length = 500)
    private String customDescription;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "note", length = 500)
    private String note;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public MethodType getMethodType() { return methodType; }
    public void setMethodType(MethodType methodType) { this.methodType = methodType; }

    public String getRailCode() { return railCode; }
    public void setRailCode(String railCode) { this.railCode = railCode; }

    public String getCustomName() { return customName; }
    public void setCustomName(String customName) { this.customName = customName; }

    public String getCustomDescription() { return customDescription; }
    public void setCustomDescription(String customDescription) { this.customDescription = customDescription; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
