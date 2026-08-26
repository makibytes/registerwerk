package de.makibytes.registerwerk.chain.internal;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("ChaincacheClient — probes chaincache's GET /api/capabilities discovery endpoint")
class ChaincacheClientTest {

    private static final String MANAGEMENT_URL = "http://chaincache:8080";
    private static final ChaincacheCredentials NO_CREDENTIAL = managementUrl -> Optional.empty();

    private ChaincacheClient client;
    private MockRestServiceServer mockServer;

    private void configure() {
        configure(NO_CREDENTIAL);
    }

    private void configure(ChaincacheCredentials credentials) {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new ChaincacheClient(builder, credentials, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    @DisplayName("returns the entry matching remoteChainKey")
    void probeCapabilities_returnsMatchingEntry() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withSuccess("""
                        [
                          {"chainKey":"anvil","durabilityDomainId":"postgres:shared-db","finalityModel":"INSTANT","safeConfirmations":0,
                           "finalizedConfirmations":0,"configuredApis":["eth","debug"],
                           "debugApiConfiguredOnAnyNode":true,"addressTraceCapability":{"attempted":true,"lastSuccessful":true,"lastAttemptAt":"2026-08-21T22:00:00Z"},
                           "durableStreamAvailable":true,"durableProtocolVersion":"2"},
                          {"chainKey":"sepolia","finalityModel":"TAG_BASED","safeConfirmations":1,
                           "finalizedConfirmations":2,"configuredApis":["eth"],
                           "debugApiConfiguredOnAnyNode":false,"addressTraceCapability":{"attempted":false,"lastSuccessful":false,"lastAttemptAt":null},
                           "durableStreamAvailable":true,"durableProtocolVersion":"2"}
                        ]
                        """, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/chains"))
                .andRespond(withSuccess("""
                        [{"chainKey":"anvil","finalityModel":"INSTANT","safeConfirmations":0,
                          "finalizedConfirmations":0,"configuredNodeCount":2,"availableNodeCount":2}]
                        """, MediaType.APPLICATION_JSON));

        Optional<ChaincacheClient.ChainCapabilitiesProbe> result =
                client.probeCapabilities(MANAGEMENT_URL, "anvil");

        assertThat(result).isPresent();
        assertThat(result.get().chainKey()).isEqualTo("anvil");
        assertThat(result.get().durabilityDomainId()).isEqualTo("postgres:shared-db");
        assertThat(result.get().finalityModel()).isEqualTo("INSTANT");
        assertThat(result.get().durableStreamAvailable()).isTrue();
        assertThat(result.get().durableProtocolVersion()).isEqualTo("2");
        assertThat(result.get().addressTraceCapability().attempted()).isTrue();
        assertThat(result.get().addressTraceCapability().lastSuccessful()).isTrue();
        assertThat(result.get().configuredNodeCount()).isEqualTo(2);
        assertThat(result.get().availableNodeCount()).isEqualTo(2);
        mockServer.verify();
    }

    @Test
    @DisplayName("regression: addressTraceCapability is chaincache's real nested status object "
            + "(attempted/lastSuccessful/lastAttemptAt), not a plain string — a prior version of "
            + "this record declared it as String, which made Jackson fail every single real probe "
            + "silently (caught as a generic RestClientException), which is why capability data "
            + "never appeared anywhere in the operator UI regardless of environment")
    void probeCapabilities_realChaincacheResponseShape_deserializesAddressTraceCapabilityAsObject() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withSuccess("""
                        [{"chainKey":"local-devnet","finalityModel":"INSTANT","safeConfirmations":6,
                          "finalizedConfirmations":32,
                          "configuredApis":["trace","debug","miner","admin","txpool","web3","net","eth"],
                          "debugApiConfiguredOnAnyNode":true,
                          "addressTraceCapability":{"attempted":false,"lastSuccessful":false,"lastAttemptAt":null},
                          "durableStreamAvailable":true,"durableProtocolVersion":"2"}]
                        """, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/chains"))
                .andRespond(withServerError());

        Optional<ChaincacheClient.ChainCapabilitiesProbe> result =
                client.probeCapabilities(MANAGEMENT_URL, "local-devnet");

        assertThat(result).isPresent();
        assertThat(result.get().addressTraceCapability()).isNotNull();
        assertThat(result.get().addressTraceCapability().attempted()).isFalse();
        assertThat(result.get().addressTraceCapability().lastSuccessful()).isFalse();
        assertThat(result.get().addressTraceCapability().lastAttemptAt()).isNull();
    }

    @Test
    @DisplayName("returns empty when no entry matches remoteChainKey")
    void probeCapabilities_emptyWhenNoMatch() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withSuccess("""
                        [{"chainKey":"sepolia","finalityModel":"TAG_BASED","safeConfirmations":1,
                          "finalizedConfirmations":2,"configuredApis":["eth"],
                          "debugApiConfiguredOnAnyNode":false,"addressTraceCapability":{"attempted":false,"lastSuccessful":false,"lastAttemptAt":null},
                          "durableStreamAvailable":true,"durableProtocolVersion":"2"}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.probeCapabilities(MANAGEMENT_URL, "anvil")).isEmpty();
    }

    @Test
    @DisplayName("returns empty (never throws) when the instance is unreachable or errors")
    void probeCapabilities_emptyOnServerError() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withServerError());

        assertThat(client.probeCapabilities(MANAGEMENT_URL, "anvil")).isEmpty();
    }

    @Test
    @DisplayName("returns empty without making a request when managementUrl or remoteChainKey is blank")
    void probeCapabilities_emptyWhenInputsBlank() {
        configure();

        assertThat(client.probeCapabilities(null, "anvil")).isEmpty();
        assertThat(client.probeCapabilities(MANAGEMENT_URL, "")).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("detect: a URL shaped like /<key>/rpc that actually answers as chaincache is CHAINCACHE")
    void detect_chaincacheShapedUrl_confirmedByProbe_isChaincache() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withSuccess("""
                        [{"chainKey":"anvil","finalityModel":"INSTANT","safeConfirmations":0,
                          "finalizedConfirmations":0,"configuredApis":["eth"],
                          "debugApiConfiguredOnAnyNode":true,"addressTraceCapability":{"attempted":true,"lastSuccessful":true,"lastAttemptAt":"2026-08-21T22:00:00Z"},
                          "durableStreamAvailable":true,"durableProtocolVersion":"2"}]
                        """, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/chains"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        ChaincacheClient.NodeDetection detection = client.detect(MANAGEMENT_URL + "/anvil/rpc");

        assertThat(detection.kind()).isEqualTo(RpcNode.NodeKind.CHAINCACHE);
        assertThat(detection.managementUrl()).isEqualTo(MANAGEMENT_URL);
        assertThat(detection.remoteChainKey()).isEqualTo("anvil");
        assertThat(detection.capabilities()).isNotNull();
        assertThat(detection.capabilities().finalityModel()).isEqualTo("INSTANT");
        mockServer.verify();
    }

    @Test
    @DisplayName("detect: a chaincache-shaped URL that fails the probe falls back to DIRECT_RPC, not an unknown state")
    void detect_chaincacheShapedUrl_probeFails_fallsBackToDirectRpc() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withServerError());

        ChaincacheClient.NodeDetection detection = client.detect(MANAGEMENT_URL + "/anvil/rpc");

        assertThat(detection.kind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        assertThat(detection.managementUrl()).isNull();
        assertThat(detection.remoteChainKey()).isNull();
        assertThat(detection.capabilities()).isNull();
    }

    @Test
    @DisplayName("detect: a plain URL not shaped like a chaincache route is DIRECT_RPC without any probe request")
    void detect_plainUrl_isDirectRpcWithoutProbing() {
        configure();

        assertThat(client.detect("http://anvil:8545").kind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        mockServer.verify();
    }

    @Test
    @DisplayName("detect: null/blank/unparsable URLs are DIRECT_RPC without throwing")
    void detect_nullOrBlankOrUnparsableUrl_isDirectRpc() {
        configure();

        assertThat(client.detect(null).kind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        assertThat(client.detect("").kind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        assertThat(client.detect("not a url").kind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        mockServer.verify();
    }

    @Test
    @DisplayName("detect: a repeat probe of an authority that just failed is skipped entirely (negative cache)")
    void detect_repeatedFailedAuthority_skipsSecondProbe() {
        configure();
        // Only ONE expectation is set up — a second network call for the same authority would
        // leave this unsatisfied (caught by mockServer.verify()) or fail with "no further requests
        // expected", either way proving the second detect() call never hit the network.
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withServerError());

        assertThat(client.detect(MANAGEMENT_URL + "/anvil/rpc").kind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        assertThat(client.detect(MANAGEMENT_URL + "/anvil/rpc").kind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);

        mockServer.verify();
    }

    @Test
    @DisplayName("detect: a successful probe clears a prior negative-cache entry, so the next detect() re-probes")
    void detect_successfulProbe_clearsNegativeCache() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withServerError());
        assertThat(client.detect(MANAGEMENT_URL + "/anvil/rpc").kind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);

        // A direct probeCapabilities() call — e.g. RpcNodeService.redetect()'s manual, on-demand
        // path — succeeds and must clear the entry the failed detect() above just set.
        mockServer.reset();
        String successBody = """
                [{"chainKey":"anvil","finalityModel":"INSTANT","safeConfirmations":0,
                  "finalizedConfirmations":0,"configuredApis":["eth"],
                  "debugApiConfiguredOnAnyNode":true,"addressTraceCapability":null,
                  "durableStreamAvailable":true,"durableProtocolVersion":"2"}]
                """;
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/chains"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(client.probeCapabilities(MANAGEMENT_URL, "anvil")).isPresent();
        mockServer.verify();

        // Now prove the clear actually took effect: a subsequent detect() on the same authority
        // hits the network again instead of being short-circuited by a still-live cache entry.
        mockServer.reset();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/chains"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(client.detect(MANAGEMENT_URL + "/anvil/rpc").kind()).isEqualTo(RpcNode.NodeKind.CHAINCACHE);
        mockServer.verify();
    }

    @Test
    @DisplayName("probeCapabilities attaches the configured bearer token as an Authorization header")
    void probeCapabilities_attachesConfiguredBearerToken() {
        configure(managementUrl -> Optional.of("test-token-123"));
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andExpect(header("Authorization", "Bearer test-token-123"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.probeCapabilities(MANAGEMENT_URL, "anvil");

        mockServer.verify();
    }

    @Test
    @DisplayName("probeCapabilities sends no Authorization header when no credential is configured")
    void probeCapabilities_noCredential_sendsNoAuthorizationHeader() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.probeCapabilities(MANAGEMENT_URL, "anvil");

        mockServer.verify();
    }

    @Test
    @DisplayName("detect: a 401 is a distinct 'unauthorized' outcome, not folded into DIRECT_RPC's other reasons")
    void detect_unauthorized_isDistinctOutcome() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ChaincacheClient.NodeDetection detection = client.detect(MANAGEMENT_URL + "/anvil/rpc");

        assertThat(detection.kind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        assertThat(detection.unauthorized()).isTrue();
        assertThat(detection.reachableButNoMatch()).isFalse();
    }

    @Test
    @DisplayName("detect: a 401 does not poison the negative cache — the next probe still hits the network")
    void detect_unauthorized_doesNotPoisonNegativeCache() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(client.detect(MANAGEMENT_URL + "/anvil/rpc").unauthorized()).isTrue();

        // A real chaincache instance is still there; the credential just needs to be fixed — the
        // very next probe (e.g. once registerwerk.chaincache.jwt-secret is corrected) must not be
        // short-circuited by NEGATIVE_CACHE_TTL the way a genuine "not chaincache" 404 would be.
        mockServer.reset();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(client.detect(MANAGEMENT_URL + "/anvil/rpc").unauthorized()).isTrue();
        mockServer.verify();
    }

    @Test
    @DisplayName("detect: a 403 is also treated as 'unauthorized', not a generic failure")
    void detect_forbidden_isUnauthorized() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(client.detect(MANAGEMENT_URL + "/anvil/rpc").unauthorized()).isTrue();
    }
}
