package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.TokenStandard;
import de.makibytes.registerwerk.domain.trading.ListingStatus;
import de.makibytes.registerwerk.domain.trading.PaymentOption;
import de.makibytes.registerwerk.domain.trading.TradingAssetType;
import de.makibytes.registerwerk.domain.trading.TradingVenueCode;

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
        Instant createdAt) {
}
