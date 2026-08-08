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
 * Talos venue adapter — multi-venue digital asset aggregation layer.
 * Activated when {@code registerwerk.trading.venues.talos.base-url} is configured.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.trading.venues.talos.base-url")
class TalosVenueAdapter implements TradingVenueAdapter {

    private static final Logger log = LoggerFactory.getLogger(TalosVenueAdapter.class);

    private final RestClient rest;
    private final TradingVenueMetadata meta;

    TalosVenueAdapter(TradingProperties properties, RestClient.Builder restClientBuilder) {
        TradingProperties.VenueProperties cfg = properties.venue(TradingVenueCode.TALOS);
        this.rest = restClientBuilder
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.getApiKey())
                .build();
        this.meta = new TradingVenueMetadata(
                TradingVenueCode.TALOS,
                "Talos",
                cfg.isEnabled(),
                cfg.isConfigured(),
                true,
                List.of(OrderType.MARKET, OrderType.LIMIT, OrderType.IOC, OrderType.FOK),
                "Multi-venue digital asset aggregation layer.");
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
                        b.path("/v1/quotes");
                        if (filter.search() != null) b.queryParam("symbol", filter.search());
                        if (filter.assetType() != null) b.queryParam("assetClass", filter.assetType().name());
                        if (filter.minPrice() != null) b.queryParam("minAsk", filter.minPrice());
                        if (filter.maxPrice() != null) b.queryParam("maxAsk", filter.maxPrice());
                        return b.build();
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            if (raw == null) return List.of();
            return raw.stream().map(this::mapOffer).toList();
        } catch (Exception e) {
            log.warn("Talos searchOffers failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public TradingVenueExecutionResult execute(ExecuteOrderRequest request) {
        try {
            var body = Map.of(
                    "side", "Buy",
                    "qty", request.quantity(),
                    "ordType", mapOrderType(request.orderType()),
                    "price", request.limitPrice() != null ? request.limitPrice() : BigDecimal.ZERO,
                    "settlementAddress", request.walletAddress());
            @SuppressWarnings("unchecked")
            Map<String, Object> response = rest.post()
                    .uri("/v1/orders")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) return TradingVenueExecutionResult.rejected("empty response");
            String orderId = String.valueOf(response.get("clOrdId"));
            return TradingVenueExecutionResult.pending(orderId);
        } catch (Exception e) {
            log.error("Talos order execution failed: {}", e.getMessage());
            return TradingVenueExecutionResult.rejected(e.getMessage());
        }
    }

    private static String mapOrderType(OrderType orderType) {
        return switch (orderType) {
            case MARKET -> "Market";
            case LIMIT -> "Limit";
            case IOC -> "Limit";
            case FOK -> "Limit";
        };
    }

    @SuppressWarnings("unchecked")
    private TradingVenueOffer mapOffer(Map<String, Object> raw) {
        return new TradingVenueOffer(
                raw.get("quoteId") != null ? UUID.fromString(String.valueOf(raw.get("quoteId"))) : null,
                TradingVenueCode.TALOS,
                "Talos",
                null,
                String.valueOf(raw.getOrDefault("symbol", "")),
                String.valueOf(raw.getOrDefault("name", "")),
                String.valueOf(raw.getOrDefault("isin", "")),
                null,
                null,
                null,
                raw.get("size") != null ? new BigDecimal(String.valueOf(raw.get("size"))) : BigDecimal.ZERO,
                raw.get("ask") != null ? new BigDecimal(String.valueOf(raw.get("ask"))) : BigDecimal.ZERO,
                Set.of(),
                List.of(OrderType.MARKET, OrderType.LIMIT, OrderType.IOC, OrderType.FOK),
                Instant.now());
    }
}
