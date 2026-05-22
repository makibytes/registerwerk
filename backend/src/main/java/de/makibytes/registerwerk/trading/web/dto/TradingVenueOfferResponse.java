package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.asset.api.TokenStandard;
import de.makibytes.registerwerk.trading.api.OrderType;
import de.makibytes.registerwerk.trading.api.PaymentOption;
import de.makibytes.registerwerk.trading.api.TradingAssetType;
import de.makibytes.registerwerk.trading.api.TradingVenueCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TradingVenueOfferResponse(
        UUID listingId,
        TradingVenueCode venueCode,
        String venueDisplayName,
        UUID assetId,
        String assetNumber,
        String assetName,
        String isin,
        TradingAssetType assetType,
        TokenStandard tokenStandard,
        Chain chain,
        BigDecimal quantityAvailable,
        BigDecimal pricePerUnit,
        List<PaymentOption> allowedPaymentOptions,
        List<OrderType> supportedOrderTypes,
        Instant createdAt) {
}
