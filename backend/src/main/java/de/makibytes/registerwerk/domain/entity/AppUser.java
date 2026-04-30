package de.makibytes.registerwerk.domain.entity;

import de.makibytes.registerwerk.domain.enums.AppUserRole;
import de.makibytes.registerwerk.domain.enums.UserAuthProvider;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "app_user")
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
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public AppUserRole getRole() { return role; }
    public void setRole(AppUserRole role) {
        this.role = role;
        this.roles = new LinkedHashSet<>();
        if (role != null) {
            this.roles.add(role);
        }
    }

    public Set<AppUserRole> getRoles() { return roles; }
    public void setRoles(Set<AppUserRole> roles) {
        this.roles = new LinkedHashSet<>(roles == null ? Set.of() : roles);
        this.role = this.roles.stream().findFirst().orElse(null);
    }

    public UUID getLegalEntityId() { return legalEntityId; }
    public void setLegalEntityId(UUID legalEntityId) { this.legalEntityId = legalEntityId; }

    public UserAuthProvider getAuthProvider() { return authProvider; }
    public void setAuthProvider(UserAuthProvider authProvider) { this.authProvider = authProvider; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public boolean hasRole(AppUserRole appUserRole) {
        return roles.contains(appUserRole);
    }
}
