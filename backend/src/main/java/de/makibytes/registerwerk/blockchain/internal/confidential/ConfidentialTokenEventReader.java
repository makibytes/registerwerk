package de.makibytes.registerwerk.blockchain.internal.confidential;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Queries the Graph Node subgraph's {@code confidentialTokenEvents} (see {@code
 * indexer/evm/subgraph/src/confidentialToken.ts}) for TRANSFER/MINT/BURN events new since a
 * given block, so a holder-initiated confidential transfer is visible to the backend and can be
 * evaluated for FATF/TFR Travel Rule obligations. Amounts stay opaque FHE ciphertext handles
 * here; {@link ConfidentialTravelRuleScreeningService} decrypts each one via the registry's
 * registered-viewer key before evaluating it.
 *
 * <p>Query/HTTP/parse failures throw rather than returning an empty list
 * — an empty list must mean "genuinely nothing new happened", not "the Graph Node is down and we
 * couldn't tell". {@link ConfidentialTravelRuleScreeningService#screenDeployment} already has an
 * error-handling path built for exactly this (records {@code lastError} on the sync state); before
 * this fix that catch block could never actually be reached because this reader silently
 * swallowed every failure into {@code List.of()} first.
 */
@Component
class ConfidentialTokenEventReader {

    private static final String QUERY = """
            query($token: String!, $fromBlock: BigInt!) {
              confidentialTokenEvents(
                where: { token: $token, blockNumber_gt: $fromBlock, eventType_in: [TRANSFER, MINT, BURN] }
                orderBy: blockNumber
                orderDirection: asc
                first: 500
              ) {
                eventType
                from
                to
                handle
                blockNumber
                transactionHash
                logIndex
              }
            }
            """;

    record ConfidentialTokenEvent(
            String eventType, String from, String to, BigInteger handle,
            long blockNumber, String transactionHash, int logIndex) {}

    /** Thrown when the Graph Node query itself fails or its response can't be parsed/contains
     *  GraphQL errors — distinct from "no new events", which returns an empty list normally. */
    static class ConfidentialEventQueryException extends RuntimeException {
        ConfidentialEventQueryException(String message) { super(message); }
        ConfidentialEventQueryException(String message, Throwable cause) { super(message, cause); }
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    ConfidentialTokenEventReader(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * @return TRANSFER/MINT/BURN events for {@code tokenAddress} with {@code blockNumber >
     *         fromBlock}, ordered ascending, or empty if the chain has no Graph Node configured
     *         or nothing new has been indexed since {@code fromBlock}.
     * @throws RestClientException if the HTTP call itself fails
     * @throws ConfidentialEventQueryException if the response contains GraphQL errors or can't be parsed
     */
    List<ConfidentialTokenEvent> eventsSince(ChainConfig chain, String tokenAddress, long fromBlock) {
        if (chain.getGraphNodeUrl() == null || chain.getGraphSubgraphName() == null) {
            return List.of();
        }
        String url = chain.getGraphNodeUrl().replaceAll("/+$", "") + "/" + chain.getGraphSubgraphName();
        Map<String, Object> requestBody = Map.of(
                "query", QUERY,
                "variables", Map.of(
                        "token", tokenAddress.toLowerCase(Locale.ROOT),
                        "fromBlock", String.valueOf(fromBlock)
                )
        );

        String responseBody = restClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(String.class);
        return parseResponse(responseBody, tokenAddress);
    }

    private List<ConfidentialTokenEvent> parseResponse(String responseBody, String tokenAddress) {
        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JacksonException e) {
            throw new ConfidentialEventQueryException(
                    "Failed to parse confidentialTokenEvents response for token=" + tokenAddress, e);
        }
        JsonNode errors = root.path("errors");
        if (!errors.isMissingNode() && errors.isArray() && !errors.isEmpty()) {
            throw new ConfidentialEventQueryException(
                    "GraphQL errors querying confidentialTokenEvents for token=" + tokenAddress + ": " + errors);
        }
        JsonNode events = root.path("data").path("confidentialTokenEvents");
        if (!events.isArray() || events.isEmpty()) {
            return List.of();
        }
        try {
            List<ConfidentialTokenEvent> result = new ArrayList<>(events.size());
            for (JsonNode e : events) {
                result.add(new ConfidentialTokenEvent(
                        e.path("eventType").asText(null),
                        e.path("from").asText(null),
                        e.path("to").asText(null),
                        new BigInteger(e.path("handle").asText("0")),
                        Long.parseLong(e.path("blockNumber").asText("0")),
                        e.path("transactionHash").asText(null),
                        e.path("logIndex").asInt(0)));
            }
            return result;
        } catch (NumberFormatException e) {
            throw new ConfidentialEventQueryException(
                    "Failed to parse confidentialTokenEvents response for token=" + tokenAddress, e);
        }
    }
}
