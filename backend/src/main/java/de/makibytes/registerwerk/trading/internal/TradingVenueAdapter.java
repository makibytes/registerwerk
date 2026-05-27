package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.TradingVenueCode;

import java.util.List;

public interface TradingVenueAdapter {

    TradingVenueMetadata metadata();

    List<TradingVenueOffer> searchOffers(TradingOfferFilter filter);

    default TradingVenueCode venueCode() {
        return metadata().code();
    }

    default TradingVenueExecutionResult execute(ExecuteOrderRequest request) {
        throw new UnsupportedOperationException("Venue " + metadata().code() + " does not support execution");
    }
}
