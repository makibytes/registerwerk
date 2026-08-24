package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import de.makibytes.registerwerk.chain.api.RpcNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("ChaincacheFinalityProbe — GET /{chain}/api/blocks/{number}/finality")
class ChaincacheFinalityProbeTest {

    private static final String MANAGEMENT_URL = "http://chaincache-sepolia:8080";

    private ChaincacheFinalityProbe probe;
    private MockRestServiceServer mockServer;

    private void configure(ChaincacheCredentials credentials) {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        probe = new ChaincacheFinalityProbe(builder, credentials);
    }

    private RpcNode chaincacheNode() {
        RpcNode node = new RpcNode();
        node.setKind(RpcNode.NodeKind.CHAINCACHE);
        node.setManagementUrl(MANAGEMENT_URL);
        node.setRemoteChainKey("sepolia");
        return node;
    }

    @Test
    @DisplayName("maps a FINALIZED response to ProbeResult.FINALIZED with its block hash")
    void observe_finalized() {
        configure(managementUrl -> Optional.empty());
        mockServer.expect(requestTo(MANAGEMENT_URL + "/sepolia/api/blocks/50/finality"))
                .andRespond(withSuccess("""
                        {"chainKey":"sepolia","blockNumber":50,"blockHash":"0xhash50",
                         "finality":"FINALIZED","observedAt":"2026-08-22T20:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        Optional<ChaincacheFinalityProbe.Observation> result = probe.observe(chaincacheNode(), 50L);

        assertThat(result).isPresent();
        assertThat(result.get().blockHash()).isEqualTo("0xhash50");
        assertThat(result.get().level()).isEqualTo(ReorgGuard.ProbeResult.FINALIZED);
        mockServer.verify();
    }

    @Test
    @DisplayName("maps SAFE and PROVISIONAL the same way")
    void observe_safeAndProvisional() {
        configure(managementUrl -> Optional.empty());
        mockServer.expect(requestTo(MANAGEMENT_URL + "/sepolia/api/blocks/51/finality"))
                .andRespond(withSuccess("""
                        {"chainKey":"sepolia","blockNumber":51,"blockHash":"0xhash51",
                         "finality":"SAFE","observedAt":"2026-08-22T20:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(probe.observe(chaincacheNode(), 51L).orElseThrow().level())
                .isEqualTo(ReorgGuard.ProbeResult.SAFE);
    }

    @Test
    @DisplayName("a 404 (no canonical observation yet) is empty, not an error")
    void observe_notFound_isEmpty() {
        configure(managementUrl -> Optional.empty());
        mockServer.expect(requestTo(MANAGEMENT_URL + "/sepolia/api/blocks/999/finality"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(probe.observe(chaincacheNode(), 999L)).isEmpty();
    }

    @Test
    @DisplayName("a node missing managementUrl/remoteChainKey is empty without any network call")
    void observe_missingMetadata_noRequest() {
        configure(managementUrl -> Optional.empty());
        RpcNode node = new RpcNode();
        node.setKind(RpcNode.NodeKind.DIRECT_RPC);

        assertThat(probe.observe(node, 1L)).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("attaches the configured bearer token as an Authorization header")
    void observe_attachesConfiguredBearerToken() {
        configure(managementUrl -> Optional.of("test-token"));
        mockServer.expect(requestTo(MANAGEMENT_URL + "/sepolia/api/blocks/50/finality"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        {"chainKey":"sepolia","blockNumber":50,"blockHash":"0xhash50",
                         "finality":"FINALIZED","observedAt":"2026-08-22T20:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        probe.observe(chaincacheNode(), 50L);

        mockServer.verify();
    }

    @Test
    @DisplayName("sends no Authorization header when no credential is configured")
    void observe_noCredential_noAuthorizationHeader() {
        configure(managementUrl -> Optional.empty());
        mockServer.expect(requestTo(MANAGEMENT_URL + "/sepolia/api/blocks/50/finality"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("""
                        {"chainKey":"sepolia","blockNumber":50,"blockHash":"0xhash50",
                         "finality":"FINALIZED","observedAt":"2026-08-22T20:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        probe.observe(chaincacheNode(), 50L);

        mockServer.verify();
    }
}
