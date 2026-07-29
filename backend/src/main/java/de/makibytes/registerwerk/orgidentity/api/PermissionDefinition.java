package de.makibytes.registerwerk.orgidentity.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A permission usable by ecosystem dApps, mirrored from the onchain PermissionRegistry.
 * The onchain id is {@code permissionHash = keccak256(code)}; code convention is
 * {@code "<dappSlug>.<action>"} for dApp permissions and {@code "registerwerk.<action>"}
 * for platform permissions.
 */
@Entity
@Table(name = "permission_definition")
public class PermissionDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 120)
    private String code;

    @Column(name = "permission_hash", nullable = false, length = 66)
    private String permissionHash;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description")
    private String description;

    /** Set when the permission was declared by a marketplace dApp manifest. */
    @Column(name = "dapp_listing_id")
    private UUID dappListingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PermissionDefinitionStatus status = PermissionDefinitionStatus.ACTIVE;

    @Column(name = "defined_tx", length = 66)
    private String definedTx;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getPermissionHash() { return permissionHash; }
    public void setPermissionHash(String permissionHash) { this.permissionHash = permissionHash; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public UUID getDappListingId() { return dappListingId; }
    public void setDappListingId(UUID dappListingId) { this.dappListingId = dappListingId; }

    public PermissionDefinitionStatus getStatus() { return status; }
    public void setStatus(PermissionDefinitionStatus status) { this.status = status; }

    public String getDefinedTx() { return definedTx; }
    public void setDefinedTx(String definedTx) { this.definedTx = definedTx; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
