package de.makibytes.registerwerk.blockchain.internal.confidential;

import de.makibytes.registerwerk.blockchain.internal.confidential.ConfidentialTokenEventReader.ConfidentialEventQueryException;
import de.makibytes.registerwerk.blockchain.internal.confidential.ConfidentialTokenEventReader.ConfidentialTokenEvent;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link ConfidentialTokenEventReader} (findings #8/#12, Phase 9) — mirrors the
 * "own small self-contained GraphQL client" testing gap this repo has had since Phase 7's
 * RepoMarketEventReader (never tested either); this reader gets coverage from the start.
 */
class ConfidentialTokenEventReaderTest {

    private static final String GRAPH_NODE_URL = "http://graph-node:8000/subgraphs/name";
    private static final String SUBGRAPH_NAME = "registerwerk/ethereum-mainnet";
    private static final String TOKEN_ADDRESS = "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private MockRestServiceServer mockServer;
    private ConfidentialTokenEventReader reader;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        reader = new ConfidentialTokenEventReader(restClientBuilder, new ObjectMapper());
    }

    private ChainConfig chainWithGraphNode() {
        ChainConfig chain = new ChainConfig();
        chain.setGraphNodeUrl(GRAPH_NODE_URL);
        chain.setGraphSubgraphName(SUBGRAPH_NAME);
        return chain;
    }

    @Test
    @DisplayName("returns empty when the chain has no Graph Node configured")
    void eventsSince_returnsEmpty_whenNoGraphNodeConfigured() {
        ChainConfig chain = new ChainConfig();

        List<ConfidentialTokenEvent> result = reader.eventsSince(chain, TOKEN_ADDRESS, 0L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("parses a TRANSFER event from the GraphQL response")
    void eventsSince_parsesTransferEvent() {
        mockServer.expect(requestTo(GRAPH_NODE_URL + "/" + SUBGRAPH_NAME))
                .andRespond(withSuccess("""
                    {"data":{"confidentialTokenEvents":[
                      {"eventType":"TRANSFER","from":"0x1111111111111111111111111111111111111111",
                       "to":"0x2222222222222222222222222222222222222222","handle":"123456789",
                       "blockNumber":"42","transactionHash":"0xdeadbeef","logIndex":"3"}
                    ]}}
                    """, MediaType.APPLICATION_JSON));

        List<ConfidentialTokenEvent> result = reader.eventsSince(chainWithGraphNode(), TOKEN_ADDRESS, 10L);

        assertThat(result).hasSize(1);
        ConfidentialTokenEvent event = result.get(0);
        assertThat(event.eventType()).isEqualTo("TRANSFER");
        assertThat(event.from()).isEqualTo("0x1111111111111111111111111111111111111111");
        assertThat(event.to()).isEqualTo("0x2222222222222222222222222222222222222222");
        assertThat(event.handle()).isEqualTo(new BigInteger("123456789"));
        assertThat(event.blockNumber()).isEqualTo(42L);
        assertThat(event.transactionHash()).isEqualTo("0xdeadbeef");
        assertThat(event.logIndex()).isEqualTo(3);
        mockServer.verify();
    }

    @Test
    @DisplayName("parses a MINT event with a null 'from'")
    void eventsSince_parsesMintEvent_withNullFrom() {
        mockServer.expect(requestTo(GRAPH_NODE_URL + "/" + SUBGRAPH_NAME))
                .andRespond(withSuccess("""
                    {"data":{"confidentialTokenEvents":[
                      {"eventType":"MINT","from":null,"to":"0x2222222222222222222222222222222222222222",
                       "handle":"999","blockNumber":"5","transactionHash":"0xabc","logIndex":"0"}
                    ]}}
                    """, MediaType.APPLICATION_JSON));

        List<ConfidentialTokenEvent> result = reader.eventsSince(chainWithGraphNode(), TOKEN_ADDRESS, 0L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).from()).isNull();
        assertThat(result.get(0).eventType()).isEqualTo("MINT");
    }

    @Test
    @DisplayName("finding #13: parses a BURN event (query now includes BURN, not just TRANSFER/MINT)")
    void eventsSince_parsesBurnEvent() {
        mockServer.expect(requestTo(GRAPH_NODE_URL + "/" + SUBGRAPH_NAME))
                .andRespond(withSuccess("""
                    {"data":{"confidentialTokenEvents":[
                      {"eventType":"BURN","from":"0x1111111111111111111111111111111111111111",
                       "to":null,"handle":"555","blockNumber":"7","transactionHash":"0xburn","logIndex":"1"}
                    ]}}
                    """, MediaType.APPLICATION_JSON));

        List<ConfidentialTokenEvent> result = reader.eventsSince(chainWithGraphNode(), TOKEN_ADDRESS, 0L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).eventType()).isEqualTo("BURN");
        assertThat(result.get(0).to()).isNull();
    }

    @Test
    @DisplayName("returns empty when the response has no data")
    void eventsSince_returnsEmpty_whenNoEventsIndexedYet() {
        mockServer.expect(requestTo(GRAPH_NODE_URL + "/" + SUBGRAPH_NAME))
                .andRespond(withSuccess("""
                    {"data":{"confidentialTokenEvents":[]}}
                    """, MediaType.APPLICATION_JSON));

        List<ConfidentialTokenEvent> result = reader.eventsSince(chainWithGraphNode(), TOKEN_ADDRESS, 0L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("finding #12: throws (does not silently return empty) on GraphQL errors in the response body")
    void eventsSince_throws_onGraphqlErrors() {
        mockServer.expect(requestTo(GRAPH_NODE_URL + "/" + SUBGRAPH_NAME))
                .andRespond(withSuccess("""
                    {"errors":[{"message":"subgraph not found"}]}
                    """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> reader.eventsSince(chainWithGraphNode(), TOKEN_ADDRESS, 0L))
                .isInstanceOf(ConfidentialEventQueryException.class)
                .hasMessageContaining("GraphQL errors");
    }

    @Test
    @DisplayName("finding #12: throws (does not silently return empty) on an HTTP error")
    void eventsSince_throws_onHttpError() {
        mockServer.expect(requestTo(GRAPH_NODE_URL + "/" + SUBGRAPH_NAME))
                .andRespond(withServerError());

        assertThatThrownBy(() -> reader.eventsSince(chainWithGraphNode(), TOKEN_ADDRESS, 0L))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("finding #12: throws (does not silently return empty) on malformed JSON")
    void eventsSince_throws_onMalformedJson() {
        mockServer.expect(requestTo(GRAPH_NODE_URL + "/" + SUBGRAPH_NAME))
                .andRespond(withSuccess("{not valid json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> reader.eventsSince(chainWithGraphNode(), TOKEN_ADDRESS, 0L))
                .isInstanceOf(ConfidentialEventQueryException.class)
                .hasMessageContaining("Failed to parse");
    }
}
