package de.makibytes.registerwerk.trading.api;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.asset.api.TokenStandard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trade_execution")
public class TradeExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "venue_code", nullable = false, length = 20)
    private TradingVenueCode venueCode;

    @Column(name = "buyer_entity_id", nullable = false)
    private UUID buyerEntityId;

    @Column(name = "seller_entity_id", nullable = false)
    private UUID sellerEntityId;

    @Column(name = "seller_holder_id", nullable = false)
    private UUID sellerHolderId;

    @Column(name = "buyer_holder_id")
    private UUID buyerHolderId;

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
    @Column(name = "order_type", nullable = false, length = 20)
    private OrderType orderType;

    @Column(name = "requested_quantity", nullable = false, precision = 38, scale = 18)
    private BigDecimal requestedQuantity;

    @Column(name = "executed_quantity", nullable = false, precision = 38, scale = 18)
    private BigDecimal executedQuantity;

    @Column(name = "unit_price", nullable = false, precision = 38, scale = 18)
    private BigDecimal unitPrice;

    @Column(name = "total_price", nullable = false, precision = 38, scale = 18)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_option", nullable = false, length = 30)
    private PaymentOption paymentOption;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false, length = 20)
    private SettlementStatus settlementStatus = SettlementStatus.SETTLED;

    @Enumerated(EnumType.STRING)
    @Column(name = "wallet_preference_mode", nullable = false, length = 30)
    private WalletPreferenceMode walletPreferenceMode;

    @Column(name = "wallet_endpoint_id")
    private UUID walletEndpointId;

    @Column(name = "wallet_address", nullable = false, length = 128)
    private String walletAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "settled_at")
    private Instant settledAt;

    public UUID getId() {
        return id;
    }

    public UUID getListingId() {
        return listingId;
    }

    public void setListingId(UUID listingId) {
        this.listingId = listingId;
    }

    public TradingVenueCode getVenueCode() {
        return venueCode;
    }

    public void setVenueCode(TradingVenueCode venueCode) {
        this.venueCode = venueCode;
    }

    public UUID getBuyerEntityId() {
        return buyerEntityId;
    }

    public void setBuyerEntityId(UUID buyerEntityId) {
        this.buyerEntityId = buyerEntityId;
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

    public UUID getBuyerHolderId() {
        return buyerHolderId;
    }

    public void setBuyerHolderId(UUID buyerHolderId) {
        this.buyerHolderId = buyerHolderId;
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

    public OrderType getOrderType() {
        return orderType;
    }

    public void setOrderType(OrderType orderType) {
        this.orderType = orderType;
    }

    public BigDecimal getRequestedQuantity() {
        return requestedQuantity;
    }

    public void setRequestedQuantity(BigDecimal requestedQuantity) {
        this.requestedQuantity = requestedQuantity;
    }

    public BigDecimal getExecutedQuantity() {
        return executedQuantity;
    }

    public void setExecutedQuantity(BigDecimal executedQuantity) {
        this.executedQuantity = executedQuantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    public PaymentOption getPaymentOption() {
        return paymentOption;
    }

    public void setPaymentOption(PaymentOption paymentOption) {
        this.paymentOption = paymentOption;
    }

    public SettlementStatus getSettlementStatus() {
        return settlementStatus;
    }

    public void setSettlementStatus(SettlementStatus settlementStatus) {
        this.settlementStatus = settlementStatus;
    }

    public WalletPreferenceMode getWalletPreferenceMode() {
        return walletPreferenceMode;
    }

    public void setWalletPreferenceMode(WalletPreferenceMode walletPreferenceMode) {
        this.walletPreferenceMode = walletPreferenceMode;
    }

    public UUID getWalletEndpointId() {
        return walletEndpointId;
    }

    public void setWalletEndpointId(UUID walletEndpointId) {
        this.walletEndpointId = walletEndpointId;
    }

    public String getWalletAddress() {
        return walletAddress;
    }

    public void setWalletAddress(String walletAddress) {
        this.walletAddress = walletAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(Instant settledAt) {
        this.settledAt = settledAt;
    }
}
