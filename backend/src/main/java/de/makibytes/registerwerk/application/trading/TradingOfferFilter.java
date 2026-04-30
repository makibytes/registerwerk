package de.makibytes.registerwerk.application.trading;

import de.makibytes.registerwerk.domain.enums.TokenStandard;
import de.makibytes.registerwerk.domain.trading.PaymentOption;
import de.makibytes.registerwerk.domain.trading.TradingAssetType;
import de.makibytes.registerwerk.domain.trading.TradingVenueCode;

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
