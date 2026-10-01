package de.makibytes.registerwerk.auth.api;

import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "app_user")
@EntityListeners(AppUserSessionStateListener.class)
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "full_name", length = 200)
    private String fullName;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AppUserRole role = AppUserRole.REGISTRY_ADMIN;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_role", joinColumns = @JoinColumn(name = "app_user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Set<AppUserRole> roles = new LinkedHashSet<>(Set.of(AppUserRole.REGISTRY_ADMIN));

    @Column(name = "legal_entity_id")
    private UUID legalEntityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 20)
    private UserAuthProvider authProvider = UserAuthProvider.LOCAL;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    // ── TOTP (step-up MFA) ──────────────────────────────────────────────────
    @Column(name = "totp_secret")
    private String totpSecret;

    /** Name of the KEK provider that wrapped {@link #totpSecret}'s data key (K3, 6-09). */
    @Column(name = "totp_secret_kid", length = 200)
    private String totpSecretKid;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled = false;

    @Column(name = "totp_enrolled_at")
    private Instant totpEnrolledAt;

    // ── Microsoft Entra ID ─────────────────────────────────────────────────
    /**
     * The Entra object id (token {@code oid}) this row mirrors. The join key between an Entra
     * principal and this account; null for LOCAL accounts. Without it the token's {@code sub}
     * (an Entra oid) and {@code app_user.id} (a DB-generated UUID) are unrelated values.
     */
    @Column(name = "entra_object_id")
    private UUID entraObjectId;

    /**
     * Home tenant of the principal (token {@code tid}). Differing from the operator's own
     * tenant is the ground truth for "this user is federated from a customer's tenant", and
     * therefore that we can neither read nor manage their authentication methods.
     */
    @Column(name = "entra_tenant_id")
    private UUID entraTenantId;

    /**
     * Advisory cache of the Graph second-factor lookup, so the nav banner costs no Graph
     * round-trip. Never an authorisation input — Conditional Access is the enforcement point.
     */
    @Column(name = "entra_mfa_registered_at")
    private Instant entraMfaRegisteredAt;

    @Column(name = "entra_mfa_checked_at")
    private Instant entraMfaCheckedAt;

    // ── Generic (non-Entra) OIDC ───────────────────────────────────────────
    /**
     * OIDC {@code sub} claim for a principal resolved via a non-Entra issuer (Okta, Keycloak,
     * ForgeRock, Auth0, …, configured via {@code JWT_ISSUER_URI}). The one identifier every OIDC
     * provider guarantees stable, unlike Entra's {@code oid}. Null for LOCAL and ENTRA accounts.
     */
    @Column(name = "external_subject")
    private String externalSubject;

    /**
     * Session tokens issued before this instant are rejected by {@code UserSessionGuardFilter}
     * ({@code iat < tokensValidAfter}). Bumped whenever the account's access changes
     * (disable/enable, roles, entity, password) so every lifecycle lever revokes live sessions.
     */
    @Column(name = "tokens_valid_after")
    private Instant tokensValidAfter;

    /**
     * Revokes every session token issued so far. JWT {@code iat} has whole-second resolution, so the
     * boundary is the next full second: tokens minted in the same second are rejected too (fail
     * closed; a re-login one second later works).
     */
    public void revokeSessions() {
        this.tokensValidAfter = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(1);
    }

    /** Bump only for persisted accounts: creating or initialising a new row must not revoke its first login. */
    private void bumpIfPersisted() {
        if (this.id != null) {
            revokeSessions();
        }
    }

    public Instant getTokensValidAfter() { return tokensValidAfter; }

    /** Last actor who changed this account's roles (null: never changed since creation). */
    @Column(name = "roles_changed_by")
    private UUID rolesChangedBy;

    @Column(name = "roles_changed_at")
    private Instant rolesChangedAt;

    /** True for a bootstrap-seeded account whose env-supplied password has not been replaced yet. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    /** Records who changed the roles, for access-review reviewer independence. */
    public void markRolesChanged(UUID actorId) {
        this.rolesChangedBy = actorId;
        this.rolesChangedAt = Instant.now();
    }

    public UUID getRolesChangedBy() { return rolesChangedBy; }
    public Instant getRolesChangedAt() { return rolesChangedAt; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) {
        if (this.passwordHash != null && !java.util.Objects.equals(this.passwordHash, passwordHash)) {
            bumpIfPersisted();
        }
        this.passwordHash = passwordHash;
    }

    public AppUserRole getRole() { return role; }
    public void setRole(AppUserRole role) {
        Set<AppUserRole> next = role == null ? Set.of() : Set.of(role);
        if (!next.equals(this.roles)) {
            bumpIfPersisted();
        }
        this.role = role;
        this.roles = new LinkedHashSet<>();
        if (role != null) {
            this.roles.add(role);
        }
    }

    public Set<AppUserRole> getRoles() { return roles; }
    public void setRoles(Set<AppUserRole> roles) {
        Set<AppUserRole> next = roles == null ? Set.of() : roles;
        if (!next.equals(this.roles)) {
            bumpIfPersisted();
        }
        this.roles = new LinkedHashSet<>(roles == null ? Set.of() : roles);
        this.role = this.roles.stream().findFirst().orElse(null);
    }

    public UUID getLegalEntityId() { return legalEntityId; }
    public void setLegalEntityId(UUID legalEntityId) {
        if (!java.util.Objects.equals(this.legalEntityId, legalEntityId)) {
            bumpIfPersisted();
        }
        this.legalEntityId = legalEntityId;
    }

    public UserAuthProvider getAuthProvider() { return authProvider; }
    public void setAuthProvider(UserAuthProvider authProvider) { this.authProvider = authProvider; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            bumpIfPersisted();
        }
        this.enabled = enabled;
    }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public boolean hasRole(AppUserRole appUserRole) { return roles.contains(appUserRole); }

    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }
    public String getTotpSecretKid() { return totpSecretKid; }
    public void setTotpSecretKid(String totpSecretKid) { this.totpSecretKid = totpSecretKid; }
    public boolean isTotpEnabled() { return totpEnabled; }
    public void setTotpEnabled(boolean totpEnabled) { this.totpEnabled = totpEnabled; }
    public Instant getTotpEnrolledAt() { return totpEnrolledAt; }
    public void setTotpEnrolledAt(Instant totpEnrolledAt) { this.totpEnrolledAt = totpEnrolledAt; }

    public UUID getEntraObjectId() { return entraObjectId; }
    public void setEntraObjectId(UUID entraObjectId) { this.entraObjectId = entraObjectId; }

    public UUID getEntraTenantId() { return entraTenantId; }
    public void setEntraTenantId(UUID entraTenantId) { this.entraTenantId = entraTenantId; }

    public Instant getEntraMfaRegisteredAt() { return entraMfaRegisteredAt; }
    public void setEntraMfaRegisteredAt(Instant v) { this.entraMfaRegisteredAt = v; }

    public Instant getEntraMfaCheckedAt() { return entraMfaCheckedAt; }
    public void setEntraMfaCheckedAt(Instant v) { this.entraMfaCheckedAt = v; }

    public String getExternalSubject() { return externalSubject; }
    public void setExternalSubject(String externalSubject) { this.externalSubject = externalSubject; }
}
