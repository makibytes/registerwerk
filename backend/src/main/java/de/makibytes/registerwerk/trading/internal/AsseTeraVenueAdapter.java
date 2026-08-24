package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.OrderType;
import de.makibytes.registerwerk.trading.api.TradingVenueCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Assetera venue adapter — MiFID-oriented tokenized securities marketplace.
 * Activated when {@code registerwerk.trading.venues.assetera.base-url} is configured.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.trading.venues.assetera.base-url")
class AsseTeraVenueAdapter implements TradingVenueAdapter {

    private static final Logger log = LoggerFactory.getLogger(AsseTeraVenueAdapter.class);

    private final RestClient rest;
    private final TradingVenueMetadata meta;

    AsseTeraVenueAdapter(TradingProperties properties, RestClient.Builder restClientBuilder) {
        TradingProperties.VenueProperties cfg = properties.venue(TradingVenueCode.ASSETERA);
        this.rest = restClientBuilder
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.getApiKey())
                .build();
        this.meta = new TradingVenueMetadata(
                TradingVenueCode.ASSETERA,
                "Assetera",
                cfg.isEnabled(),
                cfg.isConfigured(),
                true,
                List.of(OrderType.MARKET, OrderType.LIMIT, OrderType.IOC, OrderType.FOK),
                "MiFID-oriented tokenized securities venue.");
    }

    @Override
    public TradingVenueMetadata metadata() {
        return meta;
    }

    @Override
    public List<TradingVenueOffer> searchOffers(TradingOfferFilter filter) {
        try {
            var raw = rest.get()
                    .uri(b -> {
                        b.path("/api/v1/orderbook");
                        if (filter.search() != null) b.queryParam("search", filter.search());
                        if (filter.assetType() != null) b.queryParam("assetType", filter.assetType().name());
                        if (filter.tokenStandard() != null) b.queryParam("tokenStandard", filter.tokenStandard().name());
                        if (filter.minPrice() != null) b.queryParam("minPrice", filter.minPrice());
                        if (filter.maxPrice() != null) b.queryParam("maxPrice", filter.maxPrice());
                        return b.build();
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            if (raw == null) return List.of();
            return raw.stream().map(this::mapOffer).toList();
        } catch (Exception e) {
            log.warn("Assetera searchOffers failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public TradingVenueExecutionResult execute(ExecuteOrderRequest request) {
        try {
            var body = Map.of(
                    "externalListingId", request.listingId() != null ? request.listingId().toString() : "",
                    "quantity", request.quantity(),
                    "orderType", request.orderType().name(),
                    "limitPrice", request.limitPrice() != null ? request.limitPrice() : BigDecimal.ZERO,
                    "settlementAddress", request.walletAddress());
            @SuppressWarnings("unchecked")
            Map<String, Object> response = rest.post()
                    .uri("/api/v1/orders")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) return TradingVenueExecutionResult.rejected("empty response");
            String orderId = String.valueOf(response.get("orderId"));
            return TradingVenueExecutionResult.pending(orderId);
        } catch (Exception e) {
            log.error("Assetera order execution failed: {}", e.getMessage());
            return TradingVenueExecutionResult.rejected(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private TradingVenueOffer mapOffer(Map<String, Object> raw) {
        return new TradingVenueOffer(
                raw.get("id") != null ? UUID.fromString(String.valueOf(raw.get("id"))) : null,
                TradingVenueCode.ASSETERA,
                "Assetera",
                null,
                String.valueOf(raw.getOrDefault("assetNumber", "")),
                String.valueOf(raw.getOrDefault("assetName", "")),
                String.valueOf(raw.getOrDefault("isin", "")),
                null,
                null,
                null,
                raw.get("quantity") != null ? new BigDecimal(String.valueOf(raw.get("quantity"))) : BigDecimal.ZERO,
                raw.get("price") != null ? new BigDecimal(String.valueOf(raw.get("price"))) : BigDecimal.ZERO,
                Set.of(),
                List.of(OrderType.MARKET, OrderType.LIMIT),
                Instant.now());
    }
}
