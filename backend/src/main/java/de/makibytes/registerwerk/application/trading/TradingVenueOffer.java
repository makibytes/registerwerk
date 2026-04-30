package de.makibytes.registerwerk.application.trading;

import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.TokenStandard;
import de.makibytes.registerwerk.domain.trading.OrderType;
import de.makibytes.registerwerk.domain.trading.PaymentOption;
import de.makibytes.registerwerk.domain.trading.TradingAssetType;
import de.makibytes.registerwerk.domain.trading.TradingVenueCode;

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
