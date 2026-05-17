package de.makibytes.registerwerk.trading.api;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.asset.api.TokenStandard;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "trade_listing")
public class TradeListing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "venue_code", nullable = false, length = 20)
    private TradingVenueCode venueCode = TradingVenueCode.SIMULATED;

    @Column(name = "seller_entity_id", nullable = false)
    private UUID sellerEntityId;

    @Column(name = "seller_holder_id", nullable = false)
    private UUID sellerHolderId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "asset_number", nullable = false, length = 30)
    private String assetNumber;

    @Column(name = "asset_name", nullable = false, length = 500)
    private String assetName;

    @Column(length = 12)
    private String isin;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 20)
    private TradingAssetType assetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_standard", nullable = false, length = 20)
    private TokenStandard tokenStandard;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Chain chain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ListingStatus status = ListingStatus.OPEN;

    @Column(name = "quantity_total", nullable = false, precision = 38, scale = 18)
    private BigDecimal quantityTotal;

    @Column(name = "quantity_available", nullable = false, precision = 38, scale = 18)
    private BigDecimal quantityAvailable;

    @Column(name = "price_per_unit", nullable = false, precision = 38, scale = 18)
    private BigDecimal pricePerUnit;

    @ElementCollection(targetClass = PaymentOption.class)
    @CollectionTable(name = "trade_listing_payment_option", joinColumns = @JoinColumn(name = "trade_listing_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_option", nullable = false, length = 30)
    private Set<PaymentOption> allowedPaymentOptions = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

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

    public TradingVenueCode getVenueCode() {
        return venueCode;
    }

    public void setVenueCode(TradingVenueCode venueCode) {
        this.venueCode = venueCode;
    }

    public UUID getSellerEntityId() {
        return sellerEntityId;
    }

    public void setSellerEntityId(UUID sellerEntityId) {
        this.sellerEntityId = sellerEntityId;
    }

    public UUID getSellerHolderId() {
        return sellerHolderId;
    }

    public void setSellerHolderId(UUID sellerHolderId) {
        this.sellerHolderId = sellerHolderId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public void setAssetId(UUID assetId) {
        this.assetId = assetId;
    }

    public String getAssetNumber() {
        return assetNumber;
    }

    public void setAssetNumber(String assetNumber) {
        this.assetNumber = assetNumber;
    }

    public String getAssetName() {
        return assetName;
    }

    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    public String getIsin() {
        return isin;
    }

    public void setIsin(String isin) {
        this.isin = isin;
    }

    public TradingAssetType getAssetType() {
        return assetType;
    }

    public void setAssetType(TradingAssetType assetType) {
        this.assetType = assetType;
    }

    public TokenStandard getTokenStandard() {
        return tokenStandard;
    }

    public void setTokenStandard(TokenStandard tokenStandard) {
        this.tokenStandard = tokenStandard;
    }

    public Chain getChain() {
        return chain;
    }

    public void setChain(Chain chain) {
        this.chain = chain;
    }

    public ListingStatus getStatus() {
        return status;
    }

    public void setStatus(ListingStatus status) {
        this.status = status;
    }

    public BigDecimal getQuantityTotal() {
        return quantityTotal;
    }

    public void setQuantityTotal(BigDecimal quantityTotal) {
        this.quantityTotal = quantityTotal;
    }

    public BigDecimal getQuantityAvailable() {
        return quantityAvailable;
    }

    public void setQuantityAvailable(BigDecimal quantityAvailable) {
        this.quantityAvailable = quantityAvailable;
    }

    public BigDecimal getPricePerUnit() {
        return pricePerUnit;
    }

    public void setPricePerUnit(BigDecimal pricePerUnit) {
        this.pricePerUnit = pricePerUnit;
    }

    public Set<PaymentOption> getAllowedPaymentOptions() {
        return allowedPaymentOptions;
    }

    public void setAllowedPaymentOptions(Set<PaymentOption> allowedPaymentOptions) {
        this.allowedPaymentOptions = allowedPaymentOptions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
