package de.makibytes.registerwerk.domain.asset;

import de.makibytes.registerwerk.domain.enums.AssetStatus;
import de.makibytes.registerwerk.domain.enums.OnchainLevel;
import de.makibytes.registerwerk.domain.enums.TokenStandard;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "asset")
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_number", nullable = false, unique = true, length = 30)
    @NotBlank
    private String assetNumber;

    @Column(name = "issuer_id", nullable = false)
    @NotNull
    private UUID issuerId;

    @Column(nullable = false, length = 500)
    @NotBlank
    private String name;

    @Column(length = 12)
    private String isin;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_standard", nullable = false, length = 20)
    @NotNull
    private TokenStandard tokenStandard;

    @Enumerated(EnumType.STRING)
    @Column(name = "onchain_level", nullable = false, length = 10)
    @NotNull
    private OnchainLevel onchainLevel = OnchainLevel.NONE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull
    private AssetStatus status = AssetStatus.DRAFT;

    @Column(name = "termsheet_doc_id")
    private UUID termsheetDocId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "public_data", columnDefinition = "jsonb")
    private Map<String, Object> publicData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getAssetNumber() { return assetNumber; }
    public void setAssetNumber(String assetNumber) { this.assetNumber = assetNumber; }

    public UUID getIssuerId() { return issuerId; }
    public void setIssuerId(UUID issuerId) { this.issuerId = issuerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIsin() { return isin; }
    public void setIsin(String isin) { this.isin = isin; }

    public TokenStandard getTokenStandard() { return tokenStandard; }
    public void setTokenStandard(TokenStandard tokenStandard) { this.tokenStandard = tokenStandard; }

    public OnchainLevel getOnchainLevel() { return onchainLevel; }
    public void setOnchainLevel(OnchainLevel onchainLevel) { this.onchainLevel = onchainLevel; }

    public AssetStatus getStatus() { return status; }
    public void setStatus(AssetStatus status) { this.status = status; }

    public UUID getTermsheetDocId() { return termsheetDocId; }
    public void setTermsheetDocId(UUID termsheetDocId) { this.termsheetDocId = termsheetDocId; }

    public Map<String, Object> getPublicData() { return publicData; }
    public void setPublicData(Map<String, Object> publicData) { this.publicData = publicData; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
