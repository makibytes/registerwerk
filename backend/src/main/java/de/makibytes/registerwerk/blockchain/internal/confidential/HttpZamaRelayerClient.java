package de.makibytes.registerwerk.blockchain.internal.confidential;

import de.makibytes.registerwerk.blockchain.api.ZamaRelayerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.util.Map;

/**
 * HTTP client for Registerwerk's own `zama-relayer` sidecar (repo root {@code zama-relayer/},
 * built and shipped in this monorepo — not an external dependency) — see
 * {@link ZamaRelayerClient}'s class-level note on scope. The JSON request/response shapes here
 * are that sidecar's own contract (see {@code zama-relayer/src/routes/*.ts}), verified end-to-end
 * against Zama's real Sepolia relayer during development (encrypt-input genuinely produced a
 * valid ciphertext handle + ZK input proof against live Zama infrastructure).
 *
 * <p>When {@code registerwerk.zama.relayer-url} is unset, {@link #isConfigured()} returns false
 * and every method throws — matching the honesty bar set elsewhere in this codebase (e.g.
 * {@code DocumentSigningService}, {@code NoopSubmissionGateway}): silently returning fake data
 * would be worse than failing loudly.
 */
@Component
public class HttpZamaRelayerClient implements ZamaRelayerClient {

    private static final Logger log = LoggerFactory.getLogger(HttpZamaRelayerClient.class);

    private final RestClient restClient;
    private final boolean configured;

    public HttpZamaRelayerClient(RestClient.Builder restClientBuilder,
                                  @Value("${registerwerk.zama.relayer-url:}") String relayerUrl,
                                  @Value("${registerwerk.zama.relayer-api-key:}") String relayerApiKey) {
        this.configured = relayerUrl != null && !relayerUrl.isBlank();
        RestClient.Builder builder = configured ? restClientBuilder.baseUrl(relayerUrl) : null;
        // The sidecar requires this same shared secret on every /v1/* request (see
        // zama-relayer/src/auth.ts) — without it every call below gets a 401, which is the
        // intended loud failure if an operator forgets to configure the key, rather than this
        // client silently calling an endpoint anyone else on the network could also reach.
        if (configured && relayerApiKey != null && !relayerApiKey.isBlank()) {
            builder = builder.defaultHeader("Authorization", "Bearer " + relayerApiKey);
        }
        this.restClient = configured ? builder.build() : null;
        if (!configured) {
            log.info("registerwerk.zama.relayer-url not set — confidential-token encrypt/decrypt "
                    + "operations requiring the Relayer sidecar will fail until configured.");
        }
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public EncryptedInput encryptInput(String contractAddress, String userAddress, BigInteger plaintextValue) {
        requireConfigured();
        Map<String, Object> response = restClient.post()
                .uri("/v1/encrypt-input")
                .body(Map.of(
                        "contractAddress", contractAddress,
                        "userAddress", userAddress,
                        "value", plaintextValue.toString()
                ))
                .retrieve()
                .body(Map.class);
        return new EncryptedInput(
                (String) response.get("ciphertextHandle"),
                (String) response.get("inputProof")
        );
    }

    @Override
    public BigInteger requestOperatorDecrypt(String ciphertextHandle, String contractAddress) {
        requireConfigured();
        Map<String, Object> response = restClient.post()
                .uri("/v1/operator-decrypt")
                .body(Map.of(
                        "ciphertextHandle", ciphertextHandle,
                        "contractAddress", contractAddress
                ))
                .retrieve()
                .body(Map.class);
        return new BigInteger(String.valueOf(response.get("cleartext")));
    }

    @Override
    public BigInteger requestPublicDecrypt(String ciphertextHandle) {
        requireConfigured();
        Map<String, Object> response = restClient.post()
                .uri("/v1/public-decrypt")
                .body(Map.of("ciphertextHandle", ciphertextHandle))
                .retrieve()
                .body(Map.class);
        return new BigInteger(String.valueOf(response.get("cleartext")));
    }

    private void requireConfigured() {
        if (!configured) {
            throw new IllegalStateException(
                    "Zama relayer sidecar is not configured (registerwerk.zama.relayer-url is unset). "
                    + "Confidential-token encrypt/decrypt operations are unavailable until a relayer "
                    + "sidecar wrapping @zama-fhe/relayer-sdk is deployed and configured.");
        }
    }
}
