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
        String paymentReference) {
}
