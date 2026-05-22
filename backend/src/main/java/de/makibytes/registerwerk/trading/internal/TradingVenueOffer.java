package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.trading.api.OrderType;
import de.makibytes.registerwerk.trading.api.PaymentOption;
import de.makibytes.registerwerk.trading.api.TradingAssetType;
import de.makibytes.registerwerk.trading.api.TradingVenueCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record TradingVenueOffer(
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
        Set<PaymentOption> allowedPaymentOptions,
        List<OrderType> supportedOrderTypes,
        Instant createdAt) {
}
