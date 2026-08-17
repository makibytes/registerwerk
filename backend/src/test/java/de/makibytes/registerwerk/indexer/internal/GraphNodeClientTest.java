package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.indexer.internal.GraphNodeClient.GraphNodeQueryException;
import de.makibytes.registerwerk.indexer.internal.GraphNodeClient.GraphTransfer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Phase 3 fix: {@code fetchTransfers} must throw on any HTTP/GraphQL/parse failure rather than
 * returning an empty list — an empty list is reserved for "queried successfully, genuinely no new
 * transfers" so {@code GraphNodeSyncService}'s consecutive-error escalation can actually see a
 * real outage (see class Javadoc on {@link GraphNodeClient}).
 */
@DisplayName("GraphNodeClient — fetchTransfers error signaling (Phase 3)")
class GraphNodeClientTest {

    private static final String GRAPH_URL = "http://graph-node/subgraphs/name/ewpg/ethereum-mainnet";

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private GraphNodeClient client;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new GraphNodeClient(restClientBuilder, new ObjectMapper(),
                RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults());
    }

    private ChainConfig chain() {
        ChainConfig c = new ChainConfig();
        c.setIdentifier("ETHEREUM_MAINNET");
        c.setGraphNodeUrl("http://graph-node/subgraphs/name");
        c.setGraphSubgraphName("ewpg/ethereum-mainnet");
        return c;
    }

    @Test
    @DisplayName("retries an HTTP error 3 times (Phase 3 resilience), then throws — distinct from an empty result")
    void fetchTransfers_retriesThenThrowsOnHttpError() {
        // HTTP_RETRY_CONFIG: maxAttempts=3 — the mock server must see all 3 attempts.
        mockServer.expect(ExpectedCount.times(3), requestTo(GRAPH_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchTransfers(chain(), 0L, 1000, 0))
                .isInstanceOf(GraphNodeQueryException.class);
        mockServer.verify();
    }

    @Test
    @DisplayName("recovers on a transient HTTP failure followed by success — the point of retry")
    void fetchTransfers_recoversAfterTransientFailure() {
        mockServer.expect(requestTo(GRAPH_URL)).andRespond(withServerError());
        mockServer.expect(requestTo(GRAPH_URL)).andRespond(withSuccess(
                "{\"data\":{\"transfers\":[]}}", MediaType.APPLICATION_JSON));

        List<GraphTransfer> result = client.fetchTransfers(chain(), 0L, 1000, 0);

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("throws on a GraphQL-level errors array in an otherwise-200 response")
    void fetchTransfers_throwsOnGraphQlErrors() {
        mockServer.expect(requestTo(GRAPH_URL)).andRespond(withSuccess(
                "{\"errors\":[{\"message\":\"subgraph failed\"}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchTransfers(chain(), 0L, 1000, 0))
                .isInstanceOf(GraphNodeQueryException.class)
                .hasMessageContaining("GraphQL errors");
        mockServer.verify();
    }

    @Test
    @DisplayName("throws on an unparseable / unexpected response shape")
    void fetchTransfers_throwsOnUnexpectedShape() {
        mockServer.expect(requestTo(GRAPH_URL)).andRespond(withSuccess(
                "{\"data\":{\"transfers\":\"not-an-array\"}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchTransfers(chain(), 0L, 1000, 0))
                .isInstanceOf(GraphNodeQueryException.class)
                .hasMessageContaining("not an array");
        mockServer.verify();
    }

    @Test
    @DisplayName("returns a genuinely empty list when the query succeeds with zero transfers")
    void fetchTransfers_returnsEmptyList_onGenuineNoNewTransfers() {
        mockServer.expect(requestTo(GRAPH_URL)).andRespond(withSuccess(
                "{\"data\":{\"transfers\":[]}}", MediaType.APPLICATION_JSON));

        List<GraphTransfer> result = client.fetchTransfers(chain(), 0L, 1000, 0);

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("skips a single malformed transfer node without failing the whole page")
    void fetchTransfers_skipsMalformedNode_keepsRestOfPage() {
        mockServer.expect(requestTo(GRAPH_URL)).andRespond(withSuccess("""
                {"data":{"transfers":[
                  {"id":"0xtx1-0","token":{"id":"0xtoken"},"from":"0xfrom","to":"0xto",
                   "amount":"100","eventType":"TRANSFER","blockNumber":10,"blockTimestamp":1700000000,
                   "transactionHash":"0xtx1","logIndex":0}
                ]}}
                """, MediaType.APPLICATION_JSON));

        List<GraphTransfer> result = client.fetchTransfers(chain(), 0L, 1000, 0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).transactionHash()).isEqualTo("0xtx1");
        mockServer.verify();
    }
}
