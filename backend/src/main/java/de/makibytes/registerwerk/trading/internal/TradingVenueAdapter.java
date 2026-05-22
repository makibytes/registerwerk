package de.makibytes.registerwerk.trading.internal;

import java.util.List;

public interface TradingVenueAdapter {

    TradingVenueMetadata metadata();

    List<TradingVenueOffer> searchOffers(TradingOfferFilter filter);
}
