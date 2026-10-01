package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.trading.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TradeExecutionResponse(
        UUID id,
        String side,
        UUID listingId,
        TradingVenueCode venueCode,
        UUID assetId,
        String assetNumber,
        String assetName,
        String isin,
        TradingAssetType assetType,
        TokenStandard tokenStandard,
        Chain chain,
        OrderType orderType,
        BigDecimal executedQuantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        PaymentOption paymentOption,
        SettlementStatus settlementStatus,
        WalletPreferenceMode walletPreferenceMode,
        UUID walletEndpointId,
        String walletAddress,
        Instant createdAt,
        Instant settledAt,
        String failureReason,
        String paymentReference,
        Instant paymentDeclaredAt,
        // Demo instant path: register moved with NO cash leg - "SIMULATED - no cash leg".
        boolean instantSettlement,
        // PAYMENT_UNRESOLVED: why, since when, and the seller's dispute reason (if disputed).
        String disputeReason,
        Instant unresolvedAt,
        String unresolvedReason,
        // Buyer may not re-reserve this listing before this instant (5A-06 cool-down); null if none.
        Instant buyerCooldownUntil,
        // Settlement currency; null on legacy trades ("currency not recorded").
        String currency,
        String paymentRailCode,
        // Exact price*quantity before rounding and the stored rounding (null on legacy trades).
        BigDecimal totalPriceUnrounded,
        Short priceRoundingScale,
        String priceRoundingMode,
        // Buyer and seller are linked (shared beneficial owner / member / wallet) - permitted only
        // with allow-related-party-trades and excluded from the reference price.
        boolean relatedParty,
        String relatedPartyReasons) {
}
