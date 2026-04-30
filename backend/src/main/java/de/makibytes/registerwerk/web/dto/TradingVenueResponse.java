package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.trading.OrderType;
import de.makibytes.registerwerk.domain.trading.TradingVenueCode;

import java.util.List;

public record TradingVenueResponse(
        TradingVenueCode code,
        String displayName,
        boolean connected,
        boolean executable,
        List<OrderType> supportedOrderTypes,
        String summary) {
}
