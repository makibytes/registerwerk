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
    @Column(name = "role_restriction_status", nullable = false, length = 20)
    private RoleRestrictionStatus roleRestrictionStatus = RoleRestrictionStatus.STABLE;

    @Column(name = "requested_role_restricted")
    private Boolean requestedRoleRestricted;

    @Column(name = "role_restriction_tx", length = 66)
    private String roleRestrictionTx;

    @Column(name = "role_restriction_chain_config_id")
    private UUID roleRestrictionChainConfigId;

    @Column(name = "role_restriction_block_number")
    private Long roleRestrictionBlockNumber;

    @Column(name = "role_restriction_block_hash", length = 128)
    private String roleRestrictionBlockHash;

    @Column(name = "role_restriction_requested_at")
    private Instant roleRestrictionRequestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PermissionGrantStatus status = PermissionGrantStatus.PENDING;

    @Column(name = "granted_tx", length = 66)
    private String grantedTx;

    @Column(name = "granted_chain_config_id")
    private UUID grantedChainConfigId;

    @Column(name = "granted_block_number")
    private Long grantedBlockNumber;

    @Column(name = "granted_block_hash", length = 128)
    private String grantedBlockHash;

    @Column(name = "revoked_tx", length = 66)
    private String revokedTx;

    @Column(name = "revoked_chain_config_id")
    private UUID revokedChainConfigId;

    @Column(name = "revoked_block_number")
    private Long revokedBlockNumber;

    @Column(name = "revoked_block_hash", length = 128)
    private String revokedBlockHash;

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

    /**
     * Effective fail-closed value. A pending/failed request to add a restriction takes effect
     * locally immediately, while a request to lift one cannot do so before chain finality.
     */
    public boolean isRoleRestricted() {
        return roleRestricted || (roleRestrictionStatus != RoleRestrictionStatus.STABLE
                && Boolean.TRUE.equals(requestedRoleRestricted));
    }
    /** Last confirmed on-chain value, excluding a pending desired change. */
    public boolean isConfirmedRoleRestricted() { return roleRestricted; }
    public void setRoleRestricted(boolean roleRestricted) { this.roleRestricted = roleRestricted; }

    public RoleRestrictionStatus getRoleRestrictionStatus() { return roleRestrictionStatus; }
    public void setRoleRestrictionStatus(RoleRestrictionStatus roleRestrictionStatus) {
        this.roleRestrictionStatus = roleRestrictionStatus;
    }

    public Boolean getRequestedRoleRestricted() { return requestedRoleRestricted; }
    public void setRequestedRoleRestricted(Boolean requestedRoleRestricted) {
        this.requestedRoleRestricted = requestedRoleRestricted;
    }

    public String getRoleRestrictionTx() { return roleRestrictionTx; }
    public void setRoleRestrictionTx(String roleRestrictionTx) { this.roleRestrictionTx = roleRestrictionTx; }

    public UUID getRoleRestrictionChainConfigId() { return roleRestrictionChainConfigId; }
    public void setRoleRestrictionChainConfigId(UUID roleRestrictionChainConfigId) {
        this.roleRestrictionChainConfigId = roleRestrictionChainConfigId;
    }

    public Long getRoleRestrictionBlockNumber() { return roleRestrictionBlockNumber; }
    public void setRoleRestrictionBlockNumber(Long roleRestrictionBlockNumber) {
        this.roleRestrictionBlockNumber = roleRestrictionBlockNumber;
    }

    public String getRoleRestrictionBlockHash() { return roleRestrictionBlockHash; }
    public void setRoleRestrictionBlockHash(String roleRestrictionBlockHash) {
        this.roleRestrictionBlockHash = roleRestrictionBlockHash;
    }

    public Instant getRoleRestrictionRequestedAt() { return roleRestrictionRequestedAt; }
    public void setRoleRestrictionRequestedAt(Instant roleRestrictionRequestedAt) {
        this.roleRestrictionRequestedAt = roleRestrictionRequestedAt;
    }

    public PermissionGrantStatus getStatus() { return status; }
    public void setStatus(PermissionGrantStatus status) { this.status = status; }

    public String getGrantedTx() { return grantedTx; }
    public void setGrantedTx(String grantedTx) { this.grantedTx = grantedTx; }

    public UUID getGrantedChainConfigId() { return grantedChainConfigId; }
    public void setGrantedChainConfigId(UUID grantedChainConfigId) { this.grantedChainConfigId = grantedChainConfigId; }

    public Long getGrantedBlockNumber() { return grantedBlockNumber; }
    public void setGrantedBlockNumber(Long grantedBlockNumber) { this.grantedBlockNumber = grantedBlockNumber; }

    public String getGrantedBlockHash() { return grantedBlockHash; }
    public void setGrantedBlockHash(String grantedBlockHash) { this.grantedBlockHash = grantedBlockHash; }

    public String getRevokedTx() { return revokedTx; }
    public void setRevokedTx(String revokedTx) { this.revokedTx = revokedTx; }

    public UUID getRevokedChainConfigId() { return revokedChainConfigId; }
    public void setRevokedChainConfigId(UUID revokedChainConfigId) { this.revokedChainConfigId = revokedChainConfigId; }

    public Long getRevokedBlockNumber() { return revokedBlockNumber; }
    public void setRevokedBlockNumber(Long revokedBlockNumber) { this.revokedBlockNumber = revokedBlockNumber; }

    public String getRevokedBlockHash() { return revokedBlockHash; }
    public void setRevokedBlockHash(String revokedBlockHash) { this.revokedBlockHash = revokedBlockHash; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    public UUID getDualControlApproverId() { return dualControlApproverId; }
    public void setDualControlApproverId(UUID dualControlApproverId) { this.dualControlApproverId = dualControlApproverId; }

    public Instant getDualControlApprovedAt() { return dualControlApprovedAt; }
    public void setDualControlApprovedAt(Instant dualControlApprovedAt) { this.dualControlApprovedAt = dualControlApprovedAt; }
}
