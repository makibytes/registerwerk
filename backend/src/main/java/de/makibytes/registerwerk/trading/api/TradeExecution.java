package de.makibytes.registerwerk.trading.api;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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
    @Column(name = "settlement_status", nullable = false, length = 30)
    private SettlementStatus settlementStatus = SettlementStatus.SETTLED;

    @Enumerated(EnumType.STRING)
    @Column(name = "wallet_preference_mode", nullable = false, length = 30)
    private WalletPreferenceMode walletPreferenceMode;

    @Column(name = "wallet_endpoint_id")
    private UUID walletEndpointId;

    @Column(name = "wallet_address", nullable = false, length = 128)
    private String walletAddress;

    /** Settlement currency (5A-02), copied from the listing; NULL on legacy rows = "currency not recorded". */
    @Column(length = 10)
    private String currency;

    @Column(name = "payment_rail_code", length = 40)
    private String paymentRailCode;

    /** Exact {@code unitPrice * quantity} before rounding; {@code totalPrice} is this rounded to
     *  {@code priceRoundingScale} decimals using {@code priceRoundingMode}. */
    @Column(name = "total_price_unrounded", precision = 38, scale = 18)
    private BigDecimal totalPriceUnrounded;

    @Column(name = "price_rounding_scale")
    private Short priceRoundingScale;

    @Column(name = "price_rounding_mode", length = 20)
    private String priceRoundingMode;

    /** Buyer and seller are linked (shared beneficial owner / member / wallet); excluded from the reference price. */
    @Column(name = "related_party", nullable = false)
    private boolean relatedParty;

    @Column(name = "related_party_reasons", length = 300)
    private String relatedPartyReasons;

    /** User who submitted the order (RTS 22-style order record). */
    @Column(name = "created_by_actor_id")
    private UUID createdByActorId;

    @Column(name = "venue_classification", length = 20)
    private String venueClassification;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "settled_at")
    private Instant settledAt;

    /** Populated for FAILED/CANCELLED/REFUNDED executions — previously a venue rejection threw
     *  and rolled back the whole method, leaving no record at all of the attempt. */
    @Column(name = "failure_reason")
    private String failureReason;

    /**
     * Evidence of payment supplied by the buyer when settling a PENDING trade — a stablecoin tx
     * hash, a SEPA transfer reference, etc., depending on {@link #paymentOption}. Gives a later
     * reconciliation something concrete to check, though this is not yet independently verified
     * against chain/bank state.
     */
    @Column(name = "payment_reference", length = 255)
    private String paymentReference;

    /** Set when the buyer declares payment (status moves to AWAITING_SELLER_CONFIRMATION) —
     *  the clock the seller-confirmation timeout is measured against. */
    @Column(name = "payment_declared_at")
    private Instant paymentDeclaredAt;

    /** Optimistic version (V26). The pessimistic {@code findByIdForUpdate} is the primary guard;
     *  this makes any path that forgets the lock fail with a conflict instead of merging a stale
     *  whole-entity write over a SETTLED trade (timeout job vs seller confirm). */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** True only for the demo instant path (SIMULATED venue, seller opt-in): register moved with
     *  NO cash leg. Confirmations of such trades carry a "SIMULATED - no cash leg" watermark. */
    @Column(name = "instant_settlement", nullable = false)
    private boolean instantSettlement;

    /** The seller's stated reason when the payment was disputed. */
    @Column(name = "dispute_reason", length = 1000)
    private String disputeReason;

    /** When the trade entered PAYMENT_UNRESOLVED (age of the operator queue item). */
    @Column(name = "unresolved_at")
    private Instant unresolvedAt;

    @Column(name = "unresolved_reason", length = 1000)
    private String unresolvedReason;

    /** Set when the buyer cancels / lets a reservation lapse: the buyer may not re-reserve the
     *  same listing before this instant (5A-06 cool-down). */
    @Column(name = "buyer_cooldown_until")
    private Instant buyerCooldownUntil;

    public long getVersion() {
        return version;
    }

    public boolean isInstantSettlement() {
        return instantSettlement;
    }

    public void setInstantSettlement(boolean instantSettlement) {
        this.instantSettlement = instantSettlement;
    }

    public String getDisputeReason() {
        return disputeReason;
    }

    public void setDisputeReason(String disputeReason) {
        this.disputeReason = disputeReason;
    }

    public Instant getUnresolvedAt() {
        return unresolvedAt;
    }

    public void setUnresolvedAt(Instant unresolvedAt) {
        this.unresolvedAt = unresolvedAt;
    }

    public String getUnresolvedReason() {
        return unresolvedReason;
    }

    public void setUnresolvedReason(String unresolvedReason) {
        this.unresolvedReason = unresolvedReason;
    }

    public Instant getBuyerCooldownUntil() {
        return buyerCooldownUntil;
    }

    public void setBuyerCooldownUntil(Instant buyerCooldownUntil) {
        this.buyerCooldownUntil = buyerCooldownUntil;
    }

    public Instant getPaymentDeclaredAt() {
        return paymentDeclaredAt;
    }

    public void setPaymentDeclaredAt(Instant paymentDeclaredAt) {
        this.paymentDeclaredAt = paymentDeclaredAt;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getPaymentRailCode() {
        return paymentRailCode;
    }

    public void setPaymentRailCode(String paymentRailCode) {
        this.paymentRailCode = paymentRailCode;
    }

    public BigDecimal getTotalPriceUnrounded() {
        return totalPriceUnrounded;
    }

    public void setTotalPriceUnrounded(BigDecimal totalPriceUnrounded) {
        this.totalPriceUnrounded = totalPriceUnrounded;
    }

    public Short getPriceRoundingScale() {
        return priceRoundingScale;
    }

    public void setPriceRoundingScale(Short priceRoundingScale) {
        this.priceRoundingScale = priceRoundingScale;
    }

    public String getPriceRoundingMode() {
        return priceRoundingMode;
    }

    public void setPriceRoundingMode(String priceRoundingMode) {
        this.priceRoundingMode = priceRoundingMode;
    }

    public boolean isRelatedParty() {
        return relatedParty;
    }

    public void setRelatedParty(boolean relatedParty) {
        this.relatedParty = relatedParty;
    }

    public String getRelatedPartyReasons() {
        return relatedPartyReasons;
    }

    public void setRelatedPartyReasons(String relatedPartyReasons) {
        this.relatedPartyReasons = relatedPartyReasons;
    }

    public UUID getCreatedByActorId() {
        return createdByActorId;
    }

    public void setCreatedByActorId(UUID createdByActorId) {
        this.createdByActorId = createdByActorId;
    }

    public String getVenueClassification() {
        return venueClassification;
    }

    public void setVenueClassification(String venueClassification) {
        this.venueClassification = venueClassification;
    }

    public UUID getId() {
        return id;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
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
