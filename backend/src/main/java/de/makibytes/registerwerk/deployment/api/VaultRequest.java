package de.makibytes.registerwerk.deployment.api;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vault_request")
public class VaultRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "request_id", nullable = false, precision = 78, scale = 0)
    private BigInteger requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 8)
    private VaultRequestType requestType;

    @Column(name = "controller_addr", nullable = false, length = 80)
    private String controllerAddr;

    @Column(name = "owner_addr", nullable = false, length = 80)
    private String ownerAddr;

    @Column(name = "asset_amount", precision = 78, scale = 0)
    private BigInteger assetAmount;

    @Column(name = "share_amount", precision = 78, scale = 0)
    private BigInteger shareAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_status", nullable = false, length = 16)
    private VaultRequestStatus requestStatus = VaultRequestStatus.PENDING;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "fulfilled_tx", length = 80)
    private String fulfilledTx;

    @Column(name = "nav_at_fulfill", precision = 38, scale = 18)
    private BigDecimal navAtFulfill;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public BigInteger getRequestId() { return requestId; }
    public void setRequestId(BigInteger requestId) { this.requestId = requestId; }

    public VaultRequestType getRequestType() { return requestType; }
    public void setRequestType(VaultRequestType requestType) { this.requestType = requestType; }

    public String getControllerAddr() { return controllerAddr; }
    public void setControllerAddr(String controllerAddr) { this.controllerAddr = controllerAddr; }

    public String getOwnerAddr() { return ownerAddr; }
    public void setOwnerAddr(String ownerAddr) { this.ownerAddr = ownerAddr; }

    public BigInteger getAssetAmount() { return assetAmount; }
    public void setAssetAmount(BigInteger assetAmount) { this.assetAmount = assetAmount; }

    public BigInteger getShareAmount() { return shareAmount; }
    public void setShareAmount(BigInteger shareAmount) { this.shareAmount = shareAmount; }

    public VaultRequestStatus getRequestStatus() { return requestStatus; }
    public void setRequestStatus(VaultRequestStatus requestStatus) { this.requestStatus = requestStatus; }

    public Instant getRequestedAt() { return requestedAt; }

    public Instant getFulfilledAt() { return fulfilledAt; }
    public void setFulfilledAt(Instant fulfilledAt) { this.fulfilledAt = fulfilledAt; }

    public String getFulfilledTx() { return fulfilledTx; }
    public void setFulfilledTx(String fulfilledTx) { this.fulfilledTx = fulfilledTx; }

    public BigDecimal getNavAtFulfill() { return navAtFulfill; }
    public void setNavAtFulfill(BigDecimal navAtFulfill) { this.navAtFulfill = navAtFulfill; }
}
