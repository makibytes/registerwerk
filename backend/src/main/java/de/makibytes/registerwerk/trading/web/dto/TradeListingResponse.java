package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.trading.api.ListingStatus;
import de.makibytes.registerwerk.trading.api.PaymentOption;
import de.makibytes.registerwerk.trading.api.TradingAssetType;
import de.makibytes.registerwerk.trading.api.TradingVenueCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TradeListingResponse(
        UUID id,
        TradingVenueCode venueCode,
        UUID assetId,
        String assetNumber,
        String assetName,
        String isin,
        TradingAssetType assetType,
        TokenStandard tokenStandard,
        Chain chain,
        ListingStatus status,
        BigDecimal quantityTotal,
        BigDecimal quantityAvailable,
        BigDecimal pricePerUnit,
        List<PaymentOption> allowedPaymentOptions,
        Instant createdAt,
        // Most recent settled trade price for this asset, if any — a benchmark for the listed
        // price; null when the asset has never settled a trade yet.
        BigDecimal lastTradePrice,
        // Seller opted in to the demo instant path (only ever true while the demo property is on).
        boolean allowInstantSettlement,
        // Settlement currency of pricePerUnit; null on legacy listings ("currency not recorded").
        String currency,
        String paymentRailCode,
        // Bilateral listing: the only entity that may buy; null = open listing.
        UUID targetEntityId,
        // The reference price is one settled trade between unrelated parties - indicative only,
        // not a price formed on an authorised venue.
        boolean lastTradePriceIndicative) {
}
