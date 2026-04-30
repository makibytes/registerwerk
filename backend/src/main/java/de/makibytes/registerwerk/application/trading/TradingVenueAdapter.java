package de.makibytes.registerwerk.application.trading;

import java.util.List;

public interface TradingVenueAdapter {

    TradingVenueMetadata metadata();

    List<TradingVenueOffer> searchOffers(TradingOfferFilter filter);
}
