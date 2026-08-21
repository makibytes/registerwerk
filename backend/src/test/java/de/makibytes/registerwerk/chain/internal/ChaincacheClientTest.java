package de.makibytes.registerwerk.chain.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("ChaincacheClient — probes chaincache's GET /api/capabilities discovery endpoint")
class ChaincacheClientTest {

    private static final String MANAGEMENT_URL = "http://chaincache:8080";

    private ChaincacheClient client;
    private MockRestServiceServer mockServer;

    private void configure() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new ChaincacheClient(builder);
    }

    @Test
    @DisplayName("returns the entry matching remoteChainKey")
    void probeCapabilities_returnsMatchingEntry() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withSuccess("""
                        [
                          {"chainKey":"anvil","finalityModel":"INSTANT","safeConfirmations":0,
                           "finalizedConfirmations":0,"configuredApis":["eth","debug"],
                           "debugApiConfiguredOnAnyNode":true,"addressTraceCapability":"AVAILABLE",
                           "durableStreamAvailable":true,"kafkaRelayEnabled":false},
                          {"chainKey":"sepolia","finalityModel":"TAG_BASED","safeConfirmations":1,
                           "finalizedConfirmations":2,"configuredApis":["eth"],
                           "debugApiConfiguredOnAnyNode":false,"addressTraceCapability":"UNKNOWN",
                           "durableStreamAvailable":true,"kafkaRelayEnabled":false}
                        ]
                        """, MediaType.APPLICATION_JSON));

        Optional<ChaincacheClient.ChainCapabilitiesProbe> result =
                client.probeCapabilities(MANAGEMENT_URL, "anvil");

        assertThat(result).isPresent();
        assertThat(result.get().chainKey()).isEqualTo("anvil");
        assertThat(result.get().finalityModel()).isEqualTo("INSTANT");
        assertThat(result.get().durableStreamAvailable()).isTrue();
        mockServer.verify();
    }

    @Test
    @DisplayName("returns empty when no entry matches remoteChainKey")
    void probeCapabilities_emptyWhenNoMatch() {
        configure();
        mockServer.expect(requestTo(MANAGEMENT_URL + "/api/capabilities"))
                .andRespond(withSuccess("""
                        [{"chainKey":"sepolia","finalityModel":"TAG_BASED","safeConfirmations":1,
                          "finalizedConfirmations":2,"configuredApis":["eth"],
                          "debugApiConfiguredOnAnyNode":false,"addressTraceCapability":"UNKNOWN",
                          "durableStreamAvailable":true,"kafkaRelayEnabled":false}]
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
}
