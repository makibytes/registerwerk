package de.makibytes.registerwerk.marketplace.api;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

/** A permission a dApp version declares it enforces, parsed from the manifest. */
@Entity
@Table(name = "dapp_required_permission")
public class DappRequiredPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "permission_code", nullable = false, length = 120)
    private String permissionCode;

    @Column(name = "permission_hash", nullable = false, length = 66)
    private String permissionHash;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "claim_topics", columnDefinition = "bigint[]", nullable = false)
    private List<Long> claimTopics = List.of();

    @Column(name = "rationale", length = 500)
    private String rationale;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public String getPermissionCode() { return permissionCode; }
    public void setPermissionCode(String permissionCode) { this.permissionCode = permissionCode; }

    public String getPermissionHash() { return permissionHash; }
    public void setPermissionHash(String permissionHash) { this.permissionHash = permissionHash; }

    public List<Long> getClaimTopics() { return claimTopics; }
    public void setClaimTopics(List<Long> claimTopics) { this.claimTopics = claimTopics; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
}
