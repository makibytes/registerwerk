package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityPolicyProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One row per scope (GLOBAL / one TOKEN_STANDARD / one asset) assigning a {@link FinalityPolicyProfile}. */
@Entity
@Table(name = "finality_policy_assignment")
class FinalityPolicyAssignment {

    enum ScopeType { GLOBAL, TOKEN_STANDARD, ASSET }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_standard", length = 30)
    private TokenStandard tokenStandard;

    @Column(name = "asset_id")
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "profile", nullable = false, length = 20)
    private FinalityPolicyProfile profile;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    UUID getId() { return id; }

    ScopeType getScopeType() { return scopeType; }
    void setScopeType(ScopeType scopeType) { this.scopeType = scopeType; }

    TokenStandard getTokenStandard() { return tokenStandard; }
    void setTokenStandard(TokenStandard tokenStandard) { this.tokenStandard = tokenStandard; }

    UUID getAssetId() { return assetId; }
    void setAssetId(UUID assetId) { this.assetId = assetId; }

    FinalityPolicyProfile getProfile() { return profile; }
    void setProfile(FinalityPolicyProfile profile) { this.profile = profile; }

    UUID getCreatedBy() { return createdBy; }
    void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
}
