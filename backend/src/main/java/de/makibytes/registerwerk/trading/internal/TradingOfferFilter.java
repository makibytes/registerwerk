package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.trading.api.PaymentOption;
import de.makibytes.registerwerk.trading.api.TradingAssetType;
import de.makibytes.registerwerk.trading.api.TradingVenueCode;

import java.math.BigDecimal;

public record TradingOfferFilter(
        String search,
        TradingAssetType assetType,
        TokenStandard tokenStandard,
        TradingVenueCode venueCode,
        PaymentOption paymentOption,
        BigDecimal minPrice,
        BigDecimal maxPrice) {
}
