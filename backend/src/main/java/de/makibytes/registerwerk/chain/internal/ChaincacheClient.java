package de.makibytes.registerwerk.chain.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/**
 * Talks to a chaincache instance's discovery surface ({@code GET /api/chains},
 * {@code GET /api/capabilities}) — the integration contract chaincache's own P2 phase built
 * specifically for a sibling product's admin UI to consume without reading its YAML. Used by
 * {@link RpcNodeService} to probe a {@code CHAINCACHE}-kind node on add and periodically
 * thereafter, and by the operator UI (via {@code RpcNodeResponse.capabilities}) to render the
 * real capability comparison against a direct node that is this whole showcase's point.
 *
 * <p>Deliberately does not attempt the plan's aspirational "chain-id match" check: chaincache's
 * {@code GET /api/capabilities} response carries no numeric chain-id field to compare against
 * (only {@code chainKey}, the finality model, confirmations, and its API/trace/durable-stream
 * capabilities) — this probe checks reachability and that {@code remoteChainKey} actually appears
 * in the response instead, which is what the real API surface can attest to today.
 */
@Component
class ChaincacheClient {

    private static final Logger log = LoggerFactory.getLogger(ChaincacheClient.class);

    /** Mirrors chaincache's {@code api.ChainCapabilities} record shape (a separate deployable
     *  artifact — no shared library between the two products, see the portfolio plan's explicit
     *  "share the vocabulary, not the code" decision). Deserialized permissively (unknown/renamed
     *  fields on chaincache's side degrade to null here rather than break the probe). */
    record ChainCapabilitiesProbe(
            String chainKey,
            String finalityModel,
            Long safeConfirmations,
            Long finalizedConfirmations,
            List<String> configuredApis,
            Boolean debugApiConfiguredOnAnyNode,
            String addressTraceCapability,
            Boolean durableStreamAvailable,
            Boolean kafkaRelayEnabled) {}

    private final RestClient.Builder restClientBuilder;

    ChaincacheClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    /**
     * Probes {@code managementUrl}'s {@code GET /api/capabilities} and returns the entry matching
     * {@code remoteChainKey}, or empty if the instance is unreachable, returns no matching chain,
     * or responds with anything other than 2xx. Never throws — a probe failure is a normal,
     * expected outcome (the operator is often adding a node before chaincache is even up), not an
     * error the caller should have to handle specially.
     */
    Optional<ChainCapabilitiesProbe> probeCapabilities(String managementUrl, String remoteChainKey) {
        if (managementUrl == null || managementUrl.isBlank() || remoteChainKey == null || remoteChainKey.isBlank()) {
            return Optional.empty();
        }
        try {
            // .clone() before mutating: restClientBuilder is a shared field (Spring hands out one
            // prototype instance at injection time, not a fresh one per call), and baseUrl differs
            // per probe — mutating it in place would race under concurrent admin requests. The
            // request factory (10s connect / 30s read timeout) is already configured by
            // WebConfig#restClientBuilder — deliberately not overridden here, so a test can bind
            // MockRestServiceServer to the builder without a second requestFactory() call
            // silently discarding that binding's mock request factory.
            RestClient client = restClientBuilder.clone()
                    .baseUrl(managementUrl)
                    .build();
            List<ChainCapabilitiesProbe> capabilities = client.get()
                    .uri("/api/capabilities")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ChainCapabilitiesProbe>>() {});
            if (capabilities == null) {
                return Optional.empty();
            }
            return capabilities.stream()
                    .filter(c -> remoteChainKey.equals(c.chainKey()))
                    .findFirst();
        } catch (RestClientException e) {
            log.warn("Failed to probe chaincache capabilities at {} for chain={}: {}",
                    managementUrl, remoteChainKey, e.getMessage());
            return Optional.empty();
        }
    }

}
