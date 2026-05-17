package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.internal.OrderType;
import de.makibytes.registerwerk.trading.internal.TradingVenueCode;

import java.util.List;

public record TradingVenueMetadata(
        TradingVenueCode code,
        String displayName,
        boolean enabled,
        boolean connected,
        boolean executable,
        List<OrderType> supportedOrderTypes,
        String summary) {
}
