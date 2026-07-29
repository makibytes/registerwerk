package de.makibytes.registerwerk.orgidentity.api;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors a member-wallet binding in the onchain OrgRegistry: the wallet belongs to the
 * organization and carries org-scoped role codes (hashed to bytes32 onchain).
 */
@Entity
@Table(name = "org_member_wallet")
public class OrgMemberWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_registration_id", nullable = false)
    private UUID orgRegistrationId;

    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    @Column(name = "wallet_address", nullable = false, length = 66)
    private String walletAddress;

    /** Optional link to the app_user who bound this wallet; kept off-chain only (GDPR). */
    @Column(name = "app_user_id")
    private UUID appUserId;

    @Column(name = "label", length = 120)
    private String label;

    /** Org-scoped role codes (e.g. TRADER); hashed with keccak256 for the onchain call. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "roles", columnDefinition = "text[]", nullable = false)
    private List<String> roles = List.of();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MemberWalletStatus status = MemberWalletStatus.PENDING;

    @Column(name = "bound_tx", length = 66)
    private String boundTx;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "removed_at")
    private Instant removedAt;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getOrgRegistrationId() { return orgRegistrationId; }
    public void setOrgRegistrationId(UUID orgRegistrationId) { this.orgRegistrationId = orgRegistrationId; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public String getWalletAddress() { return walletAddress; }
    public void setWalletAddress(String walletAddress) { this.walletAddress = walletAddress; }

    public UUID getAppUserId() { return appUserId; }
    public void setAppUserId(UUID appUserId) { this.appUserId = appUserId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }

    public MemberWalletStatus getStatus() { return status; }
    public void setStatus(MemberWalletStatus status) { this.status = status; }

    public String getBoundTx() { return boundTx; }
    public void setBoundTx(String boundTx) { this.boundTx = boundTx; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }
}
