package de.makibytes.registerwerk.trading.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "company_trader_wallet_default")
public class CompanyTraderWalletDefault {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "legal_entity_id", nullable = false)
    private UUID legalEntityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", length = 20)
    private TradingAssetType assetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private WalletTargetType targetType;

    @Column(name = "endpoint_id")
    private UUID endpointId;

    @Column(name = "wallet_address", length = 128)
    private String walletAddress;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getLegalEntityId() {
        return legalEntityId;
    }

    public void setLegalEntityId(UUID legalEntityId) {
        this.legalEntityId = legalEntityId;
    }

    public TradingAssetType getAssetType() {
        return assetType;
    }

    public void setAssetType(TradingAssetType assetType) {
        this.assetType = assetType;
    }

    public WalletTargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(WalletTargetType targetType) {
        this.targetType = targetType;
    }

    public UUID getEndpointId() {
        return endpointId;
    }

    public void setEndpointId(UUID endpointId) {
        this.endpointId = endpointId;
    }

    public String getWalletAddress() {
        return walletAddress;
    }

    public void setWalletAddress(String walletAddress) {
        this.walletAddress = walletAddress;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
