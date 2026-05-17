package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.trading.api.OrderType;
import de.makibytes.registerwerk.trading.api.TradingVenueCode;

import java.util.List;

public record TradingVenueResponse(
        TradingVenueCode code,
        String displayName,
        boolean connected,
        boolean executable,
        List<OrderType> supportedOrderTypes,
        String summary) {
}
