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
 * Archax venue adapter — institutional digital securities exchange (FCA regulated).
 * Activated when {@code registerwerk.trading.venues.archax.base-url} is configured.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.trading.venues.archax.base-url")
class ArchaxVenueAdapter implements TradingVenueAdapter {

    private static final Logger log = LoggerFactory.getLogger(ArchaxVenueAdapter.class);

    private final RestClient rest;
    private final TradingVenueMetadata meta;

    ArchaxVenueAdapter(TradingProperties properties, RestClient.Builder restClientBuilder) {
        TradingProperties.VenueProperties cfg = properties.venue(TradingVenueCode.ARCHAX);
        this.rest = restClientBuilder
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("X-API-Key", cfg.getApiKey() != null ? cfg.getApiKey() : "")
                .build();
        this.meta = new TradingVenueMetadata(
                TradingVenueCode.ARCHAX,
                "Archax",
                cfg.isEnabled(),
                cfg.isConfigured(),
                true,
                List.of(OrderType.MARKET, OrderType.LIMIT, OrderType.IOC, OrderType.FOK),
                "Institutional digital securities exchange regulated by the FCA.");
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
                        b.path("/v1/market/orderbook");
                        if (filter.search() != null) b.queryParam("symbol", filter.search());
                        if (filter.assetType() != null) b.queryParam("instrumentType", filter.assetType().name());
                        if (filter.minPrice() != null) b.queryParam("priceFrom", filter.minPrice());
                        if (filter.maxPrice() != null) b.queryParam("priceTo", filter.maxPrice());
                        return b.build();
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            if (raw == null) return List.of();
            return raw.stream().map(this::mapOffer).toList();
        } catch (Exception e) {
            log.warn("Archax searchOffers failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public TradingVenueExecutionResult execute(ExecuteOrderRequest request) {
        try {
            var body = Map.of(
                    "side", "BUY",
                    "quantity", request.quantity(),
                    "orderType", request.orderType().name(),
                    "limitPrice", request.limitPrice() != null ? request.limitPrice() : BigDecimal.ZERO,
                    "settlementAddress", request.walletAddress());
            @SuppressWarnings("unchecked")
            Map<String, Object> response = rest.post()
                    .uri("/v1/orders")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) return TradingVenueExecutionResult.rejected("empty response");
            String orderId = String.valueOf(response.get("orderId"));
            return TradingVenueExecutionResult.pending(orderId);
        } catch (Exception e) {
            log.error("Archax order execution failed: {}", e.getMessage());
            return TradingVenueExecutionResult.rejected(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private TradingVenueOffer mapOffer(Map<String, Object> raw) {
        return new TradingVenueOffer(
                raw.get("orderId") != null ? UUID.fromString(String.valueOf(raw.get("orderId"))) : null,
                TradingVenueCode.ARCHAX,
                "Archax",
                null,
                String.valueOf(raw.getOrDefault("symbol", "")),
                String.valueOf(raw.getOrDefault("instrumentName", "")),
                String.valueOf(raw.getOrDefault("isin", "")),
                null,
                null,
                null,
                raw.get("availableQty") != null ? new BigDecimal(String.valueOf(raw.get("availableQty"))) : BigDecimal.ZERO,
                raw.get("askPrice") != null ? new BigDecimal(String.valueOf(raw.get("askPrice"))) : BigDecimal.ZERO,
                Set.of(),
                List.of(OrderType.MARKET, OrderType.LIMIT, OrderType.IOC, OrderType.FOK),
                Instant.now());
    }
}
