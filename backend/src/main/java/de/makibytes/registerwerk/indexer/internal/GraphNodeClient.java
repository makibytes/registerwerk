package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * HTTP client for querying a The Graph node's GraphQL endpoint.
 * Returns raw transfer records that the calling service maps to {@link de.makibytes.registerwerk.domain.history.TokenTransfer}.
 *
 * <p><strong>.</strong> {@link #fetchTransfers} previously swallowed every HTTP/parse/
 * GraphQL-level error into an empty list — indistinguishable from "queried successfully, no new
 * transfers", which meant {@code GraphNodeSyncService}'s {@code consecutive_errors} escalation
 * never fired for the single most common failure mode (RPC/graph-node outage): an outage just
 * silently looked like a quiet chain forever. It now throws {@link GraphNodeQueryException} for
 * every case that means "could not get real transfer data this tick" (HTTP failure, GraphQL
 * {@code errors}, unparseable/unexpected response shape) — {@code GraphNodeSyncService} already
 * has a try/catch around the whole sync tick that increments {@code consecutive_errors} and
 * eventually marks the indexer {@code ERROR}, so the fix is entirely in what this class reports,
 * not in how the caller reacts. An empty list now means what it says: the query succeeded and
 * genuinely found zero new transfers. A single malformed transfer node within an otherwise
 * successful page is still skipped-and-logged rather than failing the whole page — one bad
 * record shouldn't block every other real transfer in the same page.
 *
 * <p>{@link #fetchMeta} already followed the "signal failure, don't swallow it" contract (see its
 * own Javadoc) — this brings {@link #fetchTransfers} in line with it.
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

    private static final String META_QUERY = """
            query {
              _meta {
                block { number hash }
                hasIndexingErrors
              }
            }
            """;

    private static final String META_AT_BLOCK_QUERY = """
            query($number: Int!) {
              _meta(block: { number: $number }) {
                block { number hash }
                hasIndexingErrors
              }
            }
            """;

    /** Thrown by {@link #fetchTransfers} for anything that means "could not get real transfer
     *  data this tick" — see the class Javadoc for why this must not be swallowed into a value
     *  that looks identical to a successful empty result. */
    public static class GraphNodeQueryException extends RuntimeException {
        public GraphNodeQueryException(String message) {
            super(message);
        }
        public GraphNodeQueryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The Graph's {@code _meta} block metadata. When queried with a {@code block} argument
     * (time-travel query), {@code block.hash} is the CURRENT canonical hash of that height as of
     * right now — not what it was when it was first indexed — which is exactly the "re-fetch and
     * compare" primitive reorg detection needs.
     */
    public record BlockMeta(long blockNumber, String blockHash, boolean hasIndexingErrors) {}

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

    /** 3 attempts, exponential backoff 300ms→1200ms — only for the HTTP call itself
     *  (transient network/connect/read failures); a GraphQL-level {@code errors} array or an
     *  unparseable response is a query/schema problem retrying will not fix. */
    private static final RetryConfig HTTP_RETRY_CONFIG = RetryConfig.custom()
            .maxAttempts(3)
            .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(300), 2.0))
            .retryExceptions(RestClientException.class)
            .build();

    /** Opens after >=50% of the last 10 calls fail (min. 5 calls to judge), then fails fast for
     *  30s instead of hammering a down graph-node every sync tick. */
    private static final CircuitBreakerConfig HTTP_CIRCUIT_BREAKER_CONFIG = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .recordExceptions(RestClientException.class)
            .build();

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RetryRegistry retryRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public GraphNodeClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
            RetryRegistry retryRegistry, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.retryRegistry = retryRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * Executes one GraphQL POST through a per-chain retry (transient HTTP failures) and circuit
     * breaker (fail fast once a chain's graph-node is clearly down, rather than retrying into it
     * every 30s sync tick forever). Named per {@code chainIdentifier} so one chain's outage
     * cannot trip the breaker for every other chain's independent graph-node.
     */
    private String postGraphQl(ChainConfig chain, Map<String, Object> requestBody) {
        String url = chain.getGraphNodeUrl().replaceAll("/+$", "") + "/" + chain.getGraphSubgraphName();
        String name = "graph-node-" + chain.getIdentifier();
        Retry retry = retryRegistry.retry(name, HTTP_RETRY_CONFIG);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(name, HTTP_CIRCUIT_BREAKER_CONFIG);

        Supplier<String> call = () -> restClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(String.class);
        Supplier<String> resilient = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call));

        try {
            return resilient.get();
        } catch (CallNotPermittedException e) {
            throw new GraphNodeQueryException("Circuit breaker OPEN for Graph Node chain "
                    + chain.getIdentifier() + " — endpoint has been failing consistently: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new GraphNodeQueryException("HTTP error querying Graph Node for chain "
                    + chain.getIdentifier() + " (after retries): " + e.getMessage(), e);
        }
    }

    /**
     * Fetches a page of token transfers from the Graph Node subgraph for the given chain,
     * starting from {@code fromBlock}.
     *
     * @param chain     ChainConfig that contains the Graph Node URL and subgraph name.
     * @param fromBlock Inclusive lower bound on block number (uses {@code blockNumber_gte}).
     * @param pageSize  Maximum number of results to fetch (passed as {@code first}).
     * @param skip      Number of results to skip (passed as {@code skip}); used for pagination.
     * @return Parsed list of transfers — empty only when the query genuinely found none.
     * @throws GraphNodeQueryException on any HTTP, GraphQL, or parse-level failure — see class
     *         Javadoc for why this must not be swallowed into an empty list.
     */
    public List<GraphTransfer> fetchTransfers(ChainConfig chain, long fromBlock, int pageSize, int skip) {
        if (chain.getGraphNodeUrl() == null || chain.getGraphSubgraphName() == null) {
            log.warn("ChainConfig {} has no graphNodeUrl or graphSubgraphName; skipping Graph query",
                    chain.getIdentifier());
            return Collections.emptyList();
        }

        Map<String, Object> requestBody = Map.of(
                "query", GRAPHQL_QUERY,
                "variables", Map.of(
                        "fromBlock", String.valueOf(fromBlock),
                        "first", pageSize,
                        "skip", skip
                )
        );

        String responseBody = postGraphQl(chain, requestBody);
        return parseResponse(chain.getIdentifier(), responseBody);
    }

    /**
     * Convenience overload without a {@code skip} parameter (defaults to 0).
     */
    public List<GraphTransfer> fetchTransfers(ChainConfig chain, long fromBlock, int pageSize) {
        return fetchTransfers(chain, fromBlock, pageSize, 0);
    }

    /**
     * Fetches the Graph Node's current view of block metadata: with {@code blockNumber == null},
     * the latest indexed head; with a value, the current canonical hash at that height (a
     * "time-travel" {@code _meta(block: {number: N})} query) — the primitive
     * {@link ReorgGuard} uses to detect a fork.
     *
     * <p>Unlike {@link #fetchTransfers}, a failure here is real error signal the caller needs
     * (a reorg check that silently no-ops on every RPC hiccup is worse than one that skips a
     * tick) — so this returns {@link Optional#empty()} on any error rather than swallowing it
     * into a value that looks like "checked, no reorg". Callers must treat empty as "unknown,
     * skip this check", never as "confirmed no reorg".
     */
    public Optional<BlockMeta> fetchMeta(ChainConfig chain, Long blockNumber) {
        if (chain.getGraphNodeUrl() == null || chain.getGraphSubgraphName() == null) {
            return Optional.empty();
        }

        Map<String, Object> requestBody = blockNumber == null
                ? Map.of("query", META_QUERY)
                : Map.of("query", META_AT_BLOCK_QUERY, "variables", Map.of("number", blockNumber));

        String responseBody;
        try {
            responseBody = postGraphQl(chain, requestBody);
        } catch (GraphNodeQueryException e) {
            log.warn("Failed to fetch _meta from Graph Node for chain {}: {}",
                    chain.getIdentifier(), e.getMessage());
            return Optional.empty();
        }

        return parseMetaResponse(chain.getIdentifier(), responseBody);
    }

    private Optional<BlockMeta> parseMetaResponse(String chainIdentifier, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode errors = root.path("errors");
            if (!errors.isMissingNode() && errors.isArray() && !errors.isEmpty()) {
                log.warn("GraphQL _meta errors for chain {}: {}", chainIdentifier, errors);
                return Optional.empty();
            }

            JsonNode meta = root.path("data").path("_meta");
            if (meta.isMissingNode() || meta.isNull()) {
                return Optional.empty();
            }
            JsonNode block = meta.path("block");
            String hash = block.path("hash").asText(null);
            long number = block.path("number").asLong(-1);
            if (hash == null || number < 0) {
                return Optional.empty();
            }
            boolean hasIndexingErrors = meta.path("hasIndexingErrors").asBoolean(false);
            return Optional.of(new BlockMeta(number, hash, hasIndexingErrors));
        } catch (JacksonException e) {
            log.warn("Failed to parse Graph Node _meta response for chain {}: {}", chainIdentifier, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private List<GraphTransfer> parseResponse(String chainIdentifier, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new GraphNodeQueryException(
                    "Empty response body fetching transfers from Graph Node for chain " + chainIdentifier);
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Surface GraphQL errors returned in the response body.
            JsonNode errors = root.path("errors");
            if (!errors.isMissingNode() && errors.isArray() && !errors.isEmpty()) {
                throw new GraphNodeQueryException(
                        "GraphQL errors for chain " + chainIdentifier + ": " + errors);
            }

            JsonNode transfers = root.path("data").path("transfers");
            if (!transfers.isArray()) {
                throw new GraphNodeQueryException("Unexpected Graph Node response shape for chain "
                        + chainIdentifier + "; 'data.transfers' is not an array");
            }

            // A single malformed transfer node is skipped-and-logged, not fatal to the whole
            // page — unlike the failures above, this doesn't mean "the query itself failed".
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

        } catch (JacksonException e) {
            throw new GraphNodeQueryException(
                    "Failed to parse Graph Node response for chain " + chainIdentifier + ": " + e.getMessage(), e);
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
