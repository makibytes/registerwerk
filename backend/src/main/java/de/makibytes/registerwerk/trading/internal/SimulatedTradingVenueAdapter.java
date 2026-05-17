package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.ListingStatus;
import de.makibytes.registerwerk.trading.api.OrderType;
import de.makibytes.registerwerk.trading.api.PaymentOption;
import de.makibytes.registerwerk.trading.api.TradeListing;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import de.makibytes.registerwerk.trading.api.TradingVenueCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Component
public class SimulatedTradingVenueAdapter implements TradingVenueAdapter {

    private final TradingVenueMetadata metadata;
    private final TradeListingRepository listingRepository;

    public SimulatedTradingVenueAdapter(TradingProperties properties, TradeListingRepository listingRepository) {
        this.listingRepository = listingRepository;
        TradingProperties.VenueProperties venue = properties.venue(TradingVenueCode.SIMULATED);
        this.metadata = new TradingVenueMetadata(
                TradingVenueCode.SIMULATED,
                "Registerwerk Simulated Venue",
                venue.isEnabled(),
                true,
                true,
                List.of(OrderType.MARKET, OrderType.LIMIT),
                "Executable internal venue for end-to-end trading, settlement, and history flows."
        );
    }

    @Override
    public TradingVenueMetadata metadata() {
        return metadata;
    }

    @Override
    public List<TradingVenueOffer> searchOffers(TradingOfferFilter filter) {
        if (!metadata.enabled()) {
            return List.of();
        }
        return listingRepository.findByStatusInOrderByCreatedAtDesc(List.of(ListingStatus.OPEN, ListingStatus.PARTIALLY_FILLED))
                .stream()
                .filter(listing -> matches(listing, filter))
                .map(this::toOffer)
                .toList();
    }

    private TradingVenueOffer toOffer(TradeListing listing) {
        return new TradingVenueOffer(
                listing.getId(),
                TradingVenueCode.SIMULATED,
                metadata.displayName(),
                listing.getAssetId(),
                listing.getAssetNumber(),
                listing.getAssetName(),
                listing.getIsin(),
                listing.getAssetType(),
                listing.getTokenStandard(),
                listing.getChain(),
                listing.getQuantityAvailable(),
                listing.getPricePerUnit(),
                listing.getAllowedPaymentOptions(),
                metadata.supportedOrderTypes(),
                listing.getCreatedAt()
        );
    }

    private boolean matches(TradeListing listing, TradingOfferFilter filter) {
        if (filter == null) {
            return true;
        }
        if (filter.venueCode() != null && filter.venueCode() != TradingVenueCode.SIMULATED) {
            return false;
        }
        if (filter.assetType() != null && listing.getAssetType() != filter.assetType()) {
            return false;
        }
        if (filter.tokenStandard() != null && listing.getTokenStandard() != filter.tokenStandard()) {
            return false;
        }
        if (filter.paymentOption() != null && !listing.getAllowedPaymentOptions().contains(filter.paymentOption())) {
            return false;
        }
        if (filter.minPrice() != null && listing.getPricePerUnit().compareTo(filter.minPrice()) < 0) {
            return false;
        }
        if (filter.maxPrice() != null && listing.getPricePerUnit().compareTo(filter.maxPrice()) > 0) {
            return false;
        }
        if (filter.search() == null || filter.search().isBlank()) {
            return true;
        }
        String needle = filter.search().trim().toLowerCase(Locale.ROOT);
        return containsIgnoreCase(listing.getAssetName(), needle)
                || containsIgnoreCase(listing.getAssetNumber(), needle)
                || containsIgnoreCase(listing.getIsin(), needle);
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
