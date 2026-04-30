package de.makibytes.registerwerk.application.trading;

import de.makibytes.registerwerk.config.TradingProperties;
import de.makibytes.registerwerk.domain.trading.OrderType;
import de.makibytes.registerwerk.domain.trading.TradingVenueCode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlaceholderTradingVenueAdapter implements TradingVenueAdapter {

    private final List<TradingVenueMetadata> venues;

    public PlaceholderTradingVenueAdapter(TradingProperties properties) {
        this.venues = List.of(
                metadata(properties, TradingVenueCode.ASSETERA, "Assetera",
                        "MiFID-oriented tokenized securities venue; adapter scaffolded for later API onboarding."),
                metadata(properties, TradingVenueCode.ARCHAX, "Archax",
                        "Institutional digital securities venue; adapter scaffolded for later API onboarding."),
                metadata(properties, TradingVenueCode.TALOS, "Talos",
                        "Multi-venue aggregation layer; adapter scaffolded for later API onboarding.")
        );
    }

    @Override
    public TradingVenueMetadata metadata() {
        throw new UnsupportedOperationException("Placeholder adapter exposes multiple metadata entries");
    }

    public List<TradingVenueMetadata> metadataEntries() {
        return venues;
    }

    @Override
    public List<TradingVenueOffer> searchOffers(TradingOfferFilter filter) {
        return List.of();
    }

    private TradingVenueMetadata metadata(
            TradingProperties properties,
            TradingVenueCode code,
            String displayName,
            String summary) {
        TradingProperties.VenueProperties venue = properties.venue(code);
        return new TradingVenueMetadata(
                code,
                displayName,
                venue.isEnabled(),
                venue.isConfigured(),
                false,
                List.of(OrderType.MARKET, OrderType.LIMIT, OrderType.IOC, OrderType.FOK),
                summary
        );
    }
}
