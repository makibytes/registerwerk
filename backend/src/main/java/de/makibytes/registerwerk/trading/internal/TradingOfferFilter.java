package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.asset.api.TokenStandard;
import de.makibytes.registerwerk.trading.internal.PaymentOption;
import de.makibytes.registerwerk.trading.internal.TradingAssetType;
import de.makibytes.registerwerk.trading.internal.TradingVenueCode;

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
