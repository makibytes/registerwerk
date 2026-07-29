package de.makibytes.registerwerk.payment.api;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * An operator-curated payment method offered to marketplace dApps as a ready-made cash
 * leg. Stablecoin rails carry MiCAR metadata (issuer, LEI, authorization reference, EMT
 * flag, white paper link, redemption-at-par guarantee) so publishers and investors can see
 * under which regime the token is issued; Registerwerk is not the EMT issuer, so these
 * fields are disclosure-surfacing only, not a verified issuer-authorization check.
 * Per-chain contract addresses live in {@link PaymentRailChainAddress}.
 */
@Entity
@Table(name = "payment_rail")
public class PaymentRail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Stable, manifest-facing identifier (e.g. {@code aueur}, {@code erc7573-dvp}). */
    @Column(name = "code", nullable = false, length = 60, unique = true)
    private String code;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "rail_type", nullable = false, length = 20)
    private PaymentRailType railType;

    /** ISO-4217 currency the rail settles in. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Token decimals for stablecoin rails; null for off-chain rails. */
    @Column(name = "decimals")
    private Integer decimals;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "issuer_name", length = 200)
    private String issuerName;

    @Column(name = "issuer_lei", length = 20)
    private String issuerLei;

    /** Free-text MiCAR authorization reference (e.g. the issuer's EMI licence). */
    @Column(name = "micar_authorization", length = 200)
    private String micarAuthorization;

    /** Whether the token qualifies as an e-money token under MiCAR Title IV. */
    @Column(name = "emt_flag", nullable = false)
    private boolean emtFlag;

    /** Link to the MiCAR Title IV white paper investors are entitled to see (Art. 51). */
    @Column(name = "white_paper_url", length = 500)
    private String whitePaperUrl;

    /** Whether the issuer guarantees redemption at par at any time (MiCAR Art. 49). */
    @Column(name = "redemption_at_par", nullable = false)
    private boolean redemptionAtPar;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * Whether an operator has explicitly attested the MiCAR fields above against a real
     * external source (e.g. the EBA Art. 109 authorized-issuer register) — distinct from
     * simply having non-blank values, which are operator-entered free text with no
     * cross-check on their own. Resets to false whenever the disclosed fields change, since
     * a prior attestation no longer covers the new values.
     */
    @Column(name = "micar_verified", nullable = false)
    private boolean micarVerified = false;

    @Column(name = "micar_verified_at")
    private Instant micarVerifiedAt;

    @Column(name = "micar_verified_by")
    private UUID micarVerifiedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public PaymentRailType getRailType() { return railType; }
    public void setRailType(PaymentRailType railType) { this.railType = railType; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Integer getDecimals() { return decimals; }
    public void setDecimals(Integer decimals) { this.decimals = decimals; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIssuerName() { return issuerName; }
    public void setIssuerName(String issuerName) { this.issuerName = issuerName; }

    public String getIssuerLei() { return issuerLei; }
    public void setIssuerLei(String issuerLei) { this.issuerLei = issuerLei; }

    public String getMicarAuthorization() { return micarAuthorization; }
    public void setMicarAuthorization(String micarAuthorization) { this.micarAuthorization = micarAuthorization; }

    public boolean isEmtFlag() { return emtFlag; }
    public void setEmtFlag(boolean emtFlag) { this.emtFlag = emtFlag; }

    public String getWhitePaperUrl() { return whitePaperUrl; }
    public void setWhitePaperUrl(String whitePaperUrl) { this.whitePaperUrl = whitePaperUrl; }

    public boolean isRedemptionAtPar() { return redemptionAtPar; }
    public void setRedemptionAtPar(boolean redemptionAtPar) { this.redemptionAtPar = redemptionAtPar; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isMicarVerified() { return micarVerified; }
    public void setMicarVerified(boolean micarVerified) { this.micarVerified = micarVerified; }

    public Instant getMicarVerifiedAt() { return micarVerifiedAt; }
    public void setMicarVerifiedAt(Instant micarVerifiedAt) { this.micarVerifiedAt = micarVerifiedAt; }

    public UUID getMicarVerifiedBy() { return micarVerifiedBy; }
    public void setMicarVerifiedBy(UUID micarVerifiedBy) { this.micarVerifiedBy = micarVerifiedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
