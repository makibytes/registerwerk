package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Queries the Graph Node subgraph's {@code repoMarketEvents} (see
 * {@code indexer/evm/subgraph/src/repoMarket.ts}) for a provisional closing-event hint. The
 * response has no canonical block hash, receipt proof, or finality determination, so callers
 * must never promote it to durable liquidation evidence.
 *
 * <p>Mirrors {@code indexer.internal.GraphNodeClient}'s "HTTP/parse error -> log + return
 * empty" resilience pattern — deliberately its own small client rather than reaching into that
 * one directly, since it queries a differently-shaped entity and {@code indexer.internal} is
 * not a cross-module surface; {@code lending} already performs its own on-chain reads via
 * {@link RepoMarketOnchainReader} rather than routing through another module for them.
 */
@Component
class RepoMarketEventReader {

    private static final Logger log = LoggerFactory.getLogger(RepoMarketEventReader.class);

    private static final String QUERY = """
            query($market: String!, $actor: String!) {
              repoMarketEvents(
                where: { market: $market, actor: $actor, eventType_in: ["REPAID", "LIQUIDATED"] }
                orderBy: blockNumber
                orderDirection: desc
                first: 1
              ) {
                eventType
                projectionStatus
              }
            }
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    RepoMarketEventReader(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * @return the most recent unfinalized {@code "REPAID"} or {@code "LIQUIDATED"} hint for
     *         (marketAddress, borrowerAddress), or empty if Graph Node is unavailable or the
     *         response is not explicitly marked as a provisional projection.
     */
    Optional<UnfinalizedClosingEventHint> lastClosingEventHint(
            ChainConfig chain, String marketAddress, String borrowerAddress) {
        if (chain.getGraphNodeUrl() == null || chain.getGraphSubgraphName() == null) {
            return Optional.empty();
        }
        String url = chain.getGraphNodeUrl().replaceAll("/+$", "") + "/" + chain.getGraphSubgraphName();
        Map<String, Object> requestBody = Map.of(
                "query", QUERY,
                "variables", Map.of(
                        "market", marketAddress.toLowerCase(Locale.ROOT),
                        "actor", borrowerAddress.toLowerCase(Locale.ROOT)
                )
        );

        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            log.warn("HTTP error querying repoMarketEvents for market={} borrower={}: {}",
                    marketAddress, borrowerAddress, e.getMessage());
            return Optional.empty();
        }
        return parseResponse(responseBody, marketAddress, borrowerAddress);
    }

    private Optional<UnfinalizedClosingEventHint> parseResponse(
            String responseBody, String marketAddress, String borrowerAddress) {
        if (responseBody == null || responseBody.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode errors = root.path("errors");
            if (!errors.isMissingNode() && errors.isArray() && !errors.isEmpty()) {
                log.warn("GraphQL errors querying repoMarketEvents for market={} borrower={}: {}",
                        marketAddress, borrowerAddress, errors);
                return Optional.empty();
            }
            JsonNode events = root.path("data").path("repoMarketEvents");
            if (!events.isArray() || events.isEmpty()) {
                return Optional.empty();
            }
            String eventType = events.get(0).path("eventType").asText(null);
            String projectionStatus = events.get(0).path("projectionStatus").asText(null);
            if (!("REPAID".equals(eventType) || "LIQUIDATED".equals(eventType))
                    || !("EVENT_DERIVED".equals(projectionStatus) || "INCOMPLETE".equals(projectionStatus))) {
                log.warn("Ignoring malformed repoMarketEvents hint for market={} borrower={}",
                        marketAddress, borrowerAddress);
                return Optional.empty();
            }
            return Optional.of(new UnfinalizedClosingEventHint(eventType, projectionStatus));
        } catch (JacksonException e) {
            log.warn("Failed to parse repoMarketEvents response for market={} borrower={}: {}",
                    marketAddress, borrowerAddress, e.getMessage());
            return Optional.empty();
        }
    }

    /** Explicitly non-canonical Graph Node evidence; unsuitable for durable status changes. */
    record UnfinalizedClosingEventHint(String eventType, String projectionStatus) {}
}
