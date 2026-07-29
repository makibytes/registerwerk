package de.makibytes.registerwerk.orgidentity.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors a grant in the onchain PermissionRegistry: either an operator-issued org-level
 * grant ({@code ORG}) or an org-admin delegation of a granted permission to an org-scoped
 * role ({@code ROLE}).
 */
@Entity
@Table(name = "permission_grant")
public class PermissionGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "permission_definition_id", nullable = false)
    private UUID permissionDefinitionId;

    @Column(name = "org_registration_id", nullable = false)
    private UUID orgRegistrationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "grant_type", nullable = false, length = 10)
    private PermissionGrantType grantType;

    /** Role code for ROLE grants; null for ORG grants. */
    @Column(name = "role_code", length = 120)
    private String roleCode;

    /** Meaningful on ORG grants: when true, members additionally need a delegated role. */
    @Column(name = "role_restricted", nullable = false)
    private boolean roleRestricted;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PermissionGrantStatus status = PermissionGrantStatus.PENDING;

    @Column(name = "granted_tx", length = 66)
    private String grantedTx;

    @Column(name = "revoked_tx", length = 66)
    private String revokedTx;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "dual_control_approver_id")
    private UUID dualControlApproverId;

    @Column(name = "dual_control_approved_at")
    private Instant dualControlApprovedAt;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getPermissionDefinitionId() { return permissionDefinitionId; }
    public void setPermissionDefinitionId(UUID permissionDefinitionId) { this.permissionDefinitionId = permissionDefinitionId; }

    public UUID getOrgRegistrationId() { return orgRegistrationId; }
    public void setOrgRegistrationId(UUID orgRegistrationId) { this.orgRegistrationId = orgRegistrationId; }

    public PermissionGrantType getGrantType() { return grantType; }
    public void setGrantType(PermissionGrantType grantType) { this.grantType = grantType; }

    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }

    public boolean isRoleRestricted() { return roleRestricted; }
    public void setRoleRestricted(boolean roleRestricted) { this.roleRestricted = roleRestricted; }

    public PermissionGrantStatus getStatus() { return status; }
    public void setStatus(PermissionGrantStatus status) { this.status = status; }

    public String getGrantedTx() { return grantedTx; }
    public void setGrantedTx(String grantedTx) { this.grantedTx = grantedTx; }

    public String getRevokedTx() { return revokedTx; }
    public void setRevokedTx(String revokedTx) { this.revokedTx = revokedTx; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    public UUID getDualControlApproverId() { return dualControlApproverId; }
    public void setDualControlApproverId(UUID dualControlApproverId) { this.dualControlApproverId = dualControlApproverId; }

    public Instant getDualControlApprovedAt() { return dualControlApprovedAt; }
    public void setDualControlApprovedAt(Instant dualControlApprovedAt) { this.dualControlApprovedAt = dualControlApprovedAt; }
}
