package de.makibytes.registerwerk.blockchain.internal.confidential;

import de.makibytes.registerwerk.blockchain.api.ZamaRelayerClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link HttpZamaRelayerClient} (finding #12, Phase 9) — the real HTTP wire
 * client to the zama-relayer sidecar previously had zero test coverage, unlike the well-tested
 * reconciliation logic that consumes it via the mocked {@link ZamaRelayerClient} interface.
 */
class HttpZamaRelayerClientTest {

    private static final String RELAYER_URL = "http://zama-relayer:3001";

    private HttpZamaRelayerClient client;
    private MockRestServiceServer mockServer;

    private void configure(String url, String apiKey) {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new HttpZamaRelayerClient(builder, url, apiKey);
    }

    // ── isConfigured / fail-loud when unconfigured ──────────────────────────────

    @Test
    @DisplayName("isConfigured is false when relayer-url is blank")
    void isConfigured_false_whenUrlBlank() {
        configure("", "");

        assertThat(client.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("isConfigured is true when relayer-url is set")
    void isConfigured_true_whenUrlSet() {
        configure(RELAYER_URL, "");

        assertThat(client.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("encryptInput throws IllegalStateException when unconfigured, not a silent no-op")
    void encryptInput_throws_whenUnconfigured() {
        configure("", "");

        assertThatThrownBy(() -> client.encryptInput("0xcontract", "0xuser", BigInteger.TEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("requestOperatorDecrypt throws IllegalStateException when unconfigured")
    void requestOperatorDecrypt_throws_whenUnconfigured() {
        configure("", "");

        assertThatThrownBy(() -> client.requestOperatorDecrypt("0xhandle", "0xcontract"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("requestPublicDecrypt throws IllegalStateException when unconfigured")
    void requestPublicDecrypt_throws_whenUnconfigured() {
        configure("", "");

        assertThatThrownBy(() -> client.requestPublicDecrypt("0xhandle"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    // ── encryptInput ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("encryptInput posts the plaintext value and maps the ciphertext handle + proof")
    void encryptInput_mapsResponse() {
        configure(RELAYER_URL, "");
        mockServer.expect(requestTo(RELAYER_URL + "/v1/encrypt-input"))
                .andExpect(jsonPath("$.contractAddress").value("0xcontract"))
                .andExpect(jsonPath("$.userAddress").value("0xuser"))
                .andExpect(jsonPath("$.value").value("42"))
                .andRespond(withSuccess("""
                    {"ciphertextHandle":"0xhandle123","inputProof":"0xproofabc"}
                    """, MediaType.APPLICATION_JSON));

        ZamaRelayerClient.EncryptedInput result = client.encryptInput("0xcontract", "0xuser", BigInteger.valueOf(42));

        assertThat(result.ciphertextHandle()).isEqualTo("0xhandle123");
        assertThat(result.inputProofHex()).isEqualTo("0xproofabc");
        mockServer.verify();
    }

    // ── requestOperatorDecrypt ───────────────────────────────────────────────

    @Test
    @DisplayName("requestOperatorDecrypt posts the handle + contract and parses the cleartext")
    void requestOperatorDecrypt_mapsResponse() {
        configure(RELAYER_URL, "");
        mockServer.expect(requestTo(RELAYER_URL + "/v1/operator-decrypt"))
                .andExpect(jsonPath("$.ciphertextHandle").value("0xhandle"))
                .andExpect(jsonPath("$.contractAddress").value("0xcontract"))
                .andRespond(withSuccess("""
                    {"cleartext":"123456789"}
                    """, MediaType.APPLICATION_JSON));

        BigInteger result = client.requestOperatorDecrypt("0xhandle", "0xcontract");

        assertThat(result).isEqualTo(new BigInteger("123456789"));
        mockServer.verify();
    }

    // ── requestPublicDecrypt ─────────────────────────────────────────────────

    @Test
    @DisplayName("requestPublicDecrypt posts the handle and parses the cleartext")
    void requestPublicDecrypt_mapsResponse() {
        configure(RELAYER_URL, "");
        mockServer.expect(requestTo(RELAYER_URL + "/v1/public-decrypt"))
                .andExpect(jsonPath("$.ciphertextHandle").value("0xhandle"))
                .andRespond(withSuccess("""
                    {"cleartext":"999"}
                    """, MediaType.APPLICATION_JSON));

        BigInteger result = client.requestPublicDecrypt("0xhandle");

        assertThat(result).isEqualTo(BigInteger.valueOf(999));
        mockServer.verify();
    }

    // ── Authorization header ─────────────────────────────────────────────────

    @Test
    @DisplayName("attaches the Bearer API key header when configured")
    void attachesAuthorizationHeader_whenApiKeyConfigured() {
        configure(RELAYER_URL, "secret-key-123");
        mockServer.expect(requestTo(RELAYER_URL + "/v1/public-decrypt"))
                .andExpect(header("Authorization", "Bearer secret-key-123"))
                .andRespond(withSuccess("""
                    {"cleartext":"1"}
                    """, MediaType.APPLICATION_JSON));

        client.requestPublicDecrypt("0xhandle");

        mockServer.verify();
    }
}
