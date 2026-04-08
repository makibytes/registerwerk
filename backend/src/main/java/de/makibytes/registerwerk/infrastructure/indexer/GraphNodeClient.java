package de.makibytes.registerwerk.infrastructure.indexer;

import de.makibytes.registerwerk.domain.chain.ChainConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for querying a The Graph node's GraphQL endpoint.
 * Returns raw transfer records that the calling service maps to {@link de.makibytes.registerwerk.domain.history.TokenTransfer}.
 *
 * <p>On any HTTP or parse error, the client logs a warning and returns an empty list so that the
 * caller's error-counting / retry logic in {@code IndexerState} can handle it gracefully.
 */
@Component
public class GraphNodeClient {

    private static final Logger log = LoggerFactory.getLogger(GraphNodeClient.class);

    private static final String GRAPHQL_QUERY = """
            query($fromBlock: BigInt!, $first: Int!, $skip: Int!) {
              transfers(
                where: { blockNumber_gte: $fromBlock }
                orderBy: blockNumber
                orderDirection: asc
                first: $first
                skip: $skip
              ) {
                id
                token { id }
                from
                to
                tokenId
                amount
                eventType
                blockNumber
                blockTimestamp
                transactionHash
                logIndex
              }
            }
            """;

    /**
     * Immutable value object representing a single transfer event returned by the Graph Node.
     *
     * @param id               Composite subgraph ID, typically {@code "<txHash>-<logIndex>"}.
     * @param tokenAddress     Contract / token address (from the nested {@code token { id }} field).
     * @param from             Sender address.
     * @param to               Recipient address.
     * @param tokenId          Token ID as a string (may be null for ERC-20).
     * @param amount           Transfer amount as a string.
     * @param eventType        One of "MINT", "TRANSFER", or "BURN" as a raw string.
     * @param blockNumber      Block number in which the event was emitted.
     * @param blockTimestamp   Unix timestamp of the block (seconds).
     * @param transactionHash  Transaction hash.
     * @param logIndex         Log index within the transaction.
     */
    public record GraphTransfer(
            String id,
            String tokenAddress,
            String from,
            String to,
            String tokenId,
            String amount,
            String eventType,
            long blockNumber,
            long blockTimestamp,
            String transactionHash,
            long logIndex
    ) {}

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GraphNodeClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches a page of token transfers from the Graph Node subgraph for the given chain,
     * starting from {@code fromBlock}.
     *
     * @param chain     ChainConfig that contains the Graph Node URL and subgraph name.
     * @param fromBlock Inclusive lower bound on block number (uses {@code blockNumber_gte}).
     * @param pageSize  Maximum number of results to fetch (passed as {@code first}).
     * @param skip      Number of results to skip (passed as {@code skip}); used for pagination.
     * @return Parsed list of transfers, or an empty list on any error.
     */
    public List<GraphTransfer> fetchTransfers(ChainConfig chain, long fromBlock, int pageSize, int skip) {
        if (chain.getGraphNodeUrl() == null || chain.getGraphSubgraphName() == null) {
            log.warn("ChainConfig {} has no graphNodeUrl or graphSubgraphName; skipping Graph query",
                    chain.getIdentifier());
            return Collections.emptyList();
        }

        String url = chain.getGraphNodeUrl().replaceAll("/+$", "") + "/" + chain.getGraphSubgraphName();

        Map<String, Object> requestBody = Map.of(
                "query", GRAPHQL_QUERY,
                "variables", Map.of(
                        "fromBlock", String.valueOf(fromBlock),
                        "first", pageSize,
                        "skip", skip
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
            log.warn("HTTP error fetching transfers from Graph Node for chain {}: {}",
                    chain.getIdentifier(), e.getMessage());
            return Collections.emptyList();
        }

        return parseResponse(chain.getIdentifier(), responseBody);
    }

    /**
     * Convenience overload without a {@code skip} parameter (defaults to 0).
     */
    public List<GraphTransfer> fetchTransfers(ChainConfig chain, long fromBlock, int pageSize) {
        return fetchTransfers(chain, fromBlock, pageSize, 0);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private List<GraphTransfer> parseResponse(String chainIdentifier, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Collections.emptyList();
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Surface GraphQL errors returned in the response body.
            JsonNode errors = root.path("errors");
            if (!errors.isMissingNode() && errors.isArray() && !errors.isEmpty()) {
                log.warn("GraphQL errors for chain {}: {}", chainIdentifier, errors);
                return Collections.emptyList();
            }

            JsonNode transfers = root.path("data").path("transfers");
            if (!transfers.isArray()) {
                log.warn("Unexpected Graph Node response shape for chain {}; 'data.transfers' is not an array",
                        chainIdentifier);
                return Collections.emptyList();
            }

            List<GraphTransfer> result = new ArrayList<>(transfers.size());
            for (JsonNode node : transfers) {
                try {
                    result.add(parseTransferNode(node));
                } catch (Exception e) {
                    log.warn("Failed to parse transfer node for chain {}: {} — node={}",
                            chainIdentifier, e.getMessage(), node);
                }
            }
            return result;

        } catch (JsonProcessingException e) {
            log.warn("Failed to parse Graph Node response for chain {}: {}", chainIdentifier, e.getMessage());
            return Collections.emptyList();
        }
    }

    private GraphTransfer parseTransferNode(JsonNode node) {
        String tokenAddress = node.path("token").path("id").asText(null);

        return new GraphTransfer(
                node.path("id").asText(),
                tokenAddress,
                node.path("from").asText(null),
                node.path("to").asText(null),
                node.path("tokenId").asText(null),
                node.path("amount").asText(null),
                node.path("eventType").asText(null),
                node.path("blockNumber").asLong(0),
                node.path("blockTimestamp").asLong(0),
                node.path("transactionHash").asText(),
                node.path("logIndex").asLong(0)
        );
    }
}
