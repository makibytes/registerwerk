package de.makibytes.registerwerk.chain.internal;

import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import de.makibytes.registerwerk.chain.api.RpcNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to a chaincache instance's discovery surface ({@code GET /api/chains},
 * {@code GET /api/capabilities}) — the integration contract chaincache's own P2 phase built
 * specifically for a sibling product's admin UI to consume without reading its YAML. Used by
 * {@link RpcNodeService} to detect whether an RPC URL an operator pastes in is actually a
 * chaincache connection (see {@link #detect}) and to keep its capability snapshot fresh
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

    /** How long a URL authority that just failed to answer as chaincache is skipped by
     *  {@link #detect} before being tried again. Without this, {@code RpcNodeService#redetectAll}'s
     *  60s-tick job re-probed every enabled node's URL on every tick regardless of shape plausibility
     *  — and the {@code .../<segment>/rpc} route-shape heuristic below matches plenty of real
     *  third-party RPC endpoints (e.g. Avalanche's {@code .../ext/bc/C/rpc}), so this cache is what
     *  stops those from being hit with an outbound capability probe once a minute forever. */
    private static final Duration NEGATIVE_CACHE_TTL = Duration.ofHours(1);

    /** Mirrors chaincache's {@code api.ChainCapabilities} record shape (a separate deployable
     *  artifact — no shared library between the two products, see the portfolio plan's explicit
     *  "share the vocabulary, not the code" decision). Deserialized permissively (unknown/renamed
     *  fields on chaincache's side degrade to null here rather than break the probe). */
    record ChainCapabilitiesProbe(
            String chainKey,
            String durabilityDomainId,
            String finalityModel,
            Long safeConfirmations,
            Long finalizedConfirmations,
            List<String> configuredApis,
            Boolean debugApiConfiguredOnAnyNode,
            AddressTraceCapability addressTraceCapability,
            Boolean durableStreamAvailable,
            String durableProtocolVersion,
            Integer configuredNodeCount,
            Integer availableNodeCount) {

        /** Merges in the per-workload upstream node counts from {@code GET /api/chains} (see
         *  {@link #fetchNodeCounts}) — a separate call from {@code GET /api/capabilities}, so this
         *  probe is built in two stages rather than one. */
        ChainCapabilitiesProbe withNodeCounts(ChainSummaryProbe summary) {
            return new ChainCapabilitiesProbe(chainKey, durabilityDomainId, finalityModel, safeConfirmations,
                    finalizedConfirmations, configuredApis, debugApiConfiguredOnAnyNode, addressTraceCapability,
                    durableStreamAvailable, durableProtocolVersion,
                    summary == null ? null : summary.configuredNodeCount(),
                    summary == null ? null : summary.availableNodeCount());
        }
    }

    /** Mirrors chaincache's {@code api.ChainSummary} record shape — one row of
     *  {@code GET /api/chains}, the sibling discovery endpoint to {@code /api/capabilities} that
     *  carries the upstream-node counts capabilities itself doesn't. */
    record ChainSummaryProbe(
            String chainKey,
            String finalityModel,
            Long safeConfirmations,
            Long finalizedConfirmations,
            Integer configuredNodeCount,
            Integer availableNodeCount) {}

    /** chaincache's real {@code addressTraceCapability} field is a nested status object (e.g.
     *  {@code {"attempted":false,"lastSuccessful":false,"lastAttemptAt":null}}), not a plain
     *  string — declaring it as {@code String} here previously made every single probe response
     *  fail Jackson deserialization silently (caught as a generic {@code RestClientException} by
     *  {@link #probeCapabilities}), which is why capability data never appeared anywhere: the
     *  probe was never actually succeeding, on any chain, in any environment. */
    record AddressTraceCapability(boolean attempted, boolean lastSuccessful, String lastAttemptAt) {}

    /**
     * The outcome of {@link #detect}: either a confirmed chaincache connection (with the
     * derived {@code managementUrl}/{@code remoteChainKey} and, when the probe itself succeeded,
     * the capabilities already fetched — a second round-trip right after the first would be
     * wasteful) or a plain direct-RPC endpoint. There is no "unknown" state deliberately: a URL
     * that merely *looks* like a chaincache route but doesn't answer as one is a direct RPC node
     * as far as the operator needs to know — chaincache being temporarily down at add-time is
     * exactly the case {@link RpcNodeService}'s periodic re-detection (see its scheduled job)
     * exists to correct, not something the add flow should block on or ask the operator about.
     */
    record NodeDetection(RpcNode.NodeKind kind, String managementUrl, String remoteChainKey,
                        ChainCapabilitiesProbe capabilities, boolean reachableButNoMatch,
                        boolean unauthorized) {
        static NodeDetection directRpc() {
            return new NodeDetection(RpcNode.NodeKind.DIRECT_RPC, null, null, null, false, false);
        }

        /** A chaincache instance answered {@code GET /api/capabilities} normally (no exception —
         *  reachable, not a network/timeout/5xx failure) but the response listed no entry matching
         *  the candidate {@code remoteChainKey}. Distinguished from a plain {@link #directRpc()}
         *  outcome (unreachable, or never shaped like a chaincache route at all) specifically for
         *  {@code RpcNodeService#redetectAll}'s demotion hysteresis: this is a real "chaincache no
         *  longer serves this chain" signal that should demote immediately, not a transient blip
         *  that should be tolerated for a few ticks. */
        static NodeDetection directRpcReachableButNoMatch() {
            return new NodeDetection(RpcNode.NodeKind.DIRECT_RPC, null, null, null, true, false);
        }

        /** chaincache answered 401/403 — reachable and genuinely a chaincache instance (or at
         *  least something enforcing chaincache's auth shape), but Registerwerk's credential was
         *  missing or rejected. Distinguished from every other outcome specifically so
         *  {@code RpcNodeService} never treats a bad/missing
         *  {@code registerwerk.chaincache.jwt-secret} as evidence about what kind a node is —
         *  see {@link ChaincacheClient#attemptProbe}'s javadoc. */
        static NodeDetection directRpcUnauthorized() {
            return new NodeDetection(RpcNode.NodeKind.DIRECT_RPC, null, null, null, false, true);
        }
    }

    private final RestClient.Builder restClientBuilder;
    private final ChaincacheCredentials credentials;
    private final MeterRegistry meterRegistry;

    /** Authority (scheme+host+port) → when its negative-cache entry expires. Populated by
     *  {@link #probeCapabilities} on a failed probe, cleared on a successful one. */
    private final Map<String, Instant> negativeCacheExpiry = new ConcurrentHashMap<>();

    ChaincacheClient(RestClient.Builder restClientBuilder, ChaincacheCredentials credentials, MeterRegistry meterRegistry) {
        this.restClientBuilder = restClientBuilder;
        this.credentials = credentials;
        this.meterRegistry = meterRegistry;
    }

    private void recordProbeFailure(String managementUrl, String reason) {
        Counter.builder("registerwerk_chaincache_capability_probe_failures_total")
                .tags("management_url", managementUrl == null ? "unknown" : managementUrl, "reason", reason)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Determines whether {@code url} is a chaincache connection, with no operator input beyond
     * the URL itself — the whole point being that an operator should never have to know or
     * declare "this one is chaincache" themselves. chaincache's own route convention is always
     * {@code /<chainKey>/rpc} (see its {@code docs/DEVELOPER.md} multi-chain section); a URL
     * shaped that way has its scheme+host+port taken as a candidate {@code managementUrl} and its
     * last path segment as a candidate {@code remoteChainKey}, then verified via
     * {@link #probeCapabilities} — a URL merely *shaped* like a chaincache route that doesn't
     * actually answer as one (a coincidence, or a direct node that happens to proxy under a
     * similar path) is correctly treated as direct RPC, not guessed at.
     */
    NodeDetection detect(String url) {
        if (url == null || url.isBlank()) {
            return NodeDetection.directRpc();
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return NodeDetection.directRpc();
        }
        if (uri.getScheme() == null || uri.getHost() == null || uri.getPath() == null) {
            return NodeDetection.directRpc();
        }
        List<String> segments = List.of(uri.getPath().split("/")).stream()
                .filter(s -> !s.isBlank())
                .toList();
        if (segments.size() < 2 || !"rpc".equals(segments.get(segments.size() - 1))) {
            return NodeDetection.directRpc();
        }
        String candidateKey = segments.get(segments.size() - 2);
        String candidateManagementUrl = uri.getScheme() + "://" + uri.getAuthority();

        Instant expiry = negativeCacheExpiry.get(candidateManagementUrl);
        if (expiry != null && expiry.isAfter(Instant.now())) {
            return NodeDetection.directRpc();
        }

        ProbeAttempt attempt = attemptProbe(candidateManagementUrl, candidateKey);
        if (attempt.unauthorized()) {
            return NodeDetection.directRpcUnauthorized();
        }
        if (attempt.match().isEmpty()) {
            return attempt.reachable() ? NodeDetection.directRpcReachableButNoMatch() : NodeDetection.directRpc();
        }
        return new NodeDetection(RpcNode.NodeKind.CHAINCACHE, candidateManagementUrl, candidateKey,
                attempt.match().get(), false, false);
    }

    /**
     * Probes {@code managementUrl}'s {@code GET /api/capabilities} and returns the entry matching
     * {@code remoteChainKey}, or empty if the instance is unreachable, returns no matching chain,
     * or responds with anything other than 2xx. Never throws — a probe failure is a normal,
     * expected outcome (the operator is often adding a node before chaincache is even up), not an
     * error the caller should have to handle specially.
     */
    Optional<ChainCapabilitiesProbe> probeCapabilities(String managementUrl, String remoteChainKey) {
        return attemptProbe(managementUrl, remoteChainKey).match();
    }

    /** Whether the most recent {@code attemptProbe} for this {@code managementUrl} got an actual
     *  HTTP response (reachable=true) or unreachable=false. {@code unauthorized} is a distinct
     *  401/403 outcome — see {@link NodeDetection#directRpcUnauthorized()}'s javadoc for why it's
     *  never folded into "unreachable" or "no match". */
    private record ProbeAttempt(Optional<ChainCapabilitiesProbe> match, boolean reachable, boolean unauthorized) {
        private static ProbeAttempt empty(boolean reachable) {
            return new ProbeAttempt(Optional.empty(), reachable, false);
        }
    }

    private ProbeAttempt attemptProbe(String managementUrl, String remoteChainKey) {
        if (managementUrl == null || managementUrl.isBlank() || remoteChainKey == null || remoteChainKey.isBlank()) {
            return ProbeAttempt.empty(false);
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
                    .headers(headers -> credentials.bearerFor(managementUrl).ifPresent(headers::setBearerAuth))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ChainCapabilitiesProbe>>() {});
            if (capabilities == null) {
                negativeCacheExpiry.put(managementUrl, Instant.now().plus(NEGATIVE_CACHE_TTL));
                return ProbeAttempt.empty(true);
            }
            Optional<ChainCapabilitiesProbe> match = capabilities.stream()
                    .filter(c -> remoteChainKey.equals(c.chainKey()))
                    .findFirst();
            if (match.isPresent()) {
                negativeCacheExpiry.remove(managementUrl);
                match = Optional.of(match.get().withNodeCounts(fetchNodeCounts(client, managementUrl, remoteChainKey)));
            } else {
                negativeCacheExpiry.put(managementUrl, Instant.now().plus(NEGATIVE_CACHE_TTL));
                recordProbeFailure(managementUrl, "chain_missing");
            }
            return new ProbeAttempt(match, true, false);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                // Deliberately does NOT populate the negative cache: this authority IS answering
                // as chaincache (or at least enforcing its auth shape) — the failure is a
                // Registerwerk-side credential problem, not evidence the URL isn't chaincache.
                // Caching it here would mean fixing registerwerk.chaincache.jwt-secret still
                // wouldn't be noticed for up to NEGATIVE_CACHE_TTL.
                log.warn("chaincache at {} rejected Registerwerk's credential ({}) for chain={} — "
                        + "set registerwerk.chaincache.jwt-secret to match this instance's "
                        + "chaincache.jwt.secret (or disable chaincache.auth.enabled for a "
                        + "non-production instance).", managementUrl, e.getStatusCode().value(), remoteChainKey);
                recordProbeFailure(managementUrl, "unauthorized");
                return new ProbeAttempt(Optional.empty(), true, true);
            }
            return failed(managementUrl, remoteChainKey, e);
        } catch (RestClientException e) {
            return failed(managementUrl, remoteChainKey, e);
        }
    }

    /**
     * Best-effort second call to {@code GET /api/chains} for the upstream node counts that
     * {@code /api/capabilities} doesn't carry — {@code client} is already built and authenticated
     * for this {@code managementUrl}, so this reuses it rather than building a second one. Any
     * failure here (including a genuinely absent {@code /api/chains} route on an older chaincache
     * deployment) degrades to {@code null} counts rather than failing the whole probe: the
     * capabilities match this augments already succeeded, and node-count display is a nicety, not
     * something the rest of {@link #detect}/{@link #probeCapabilities}'s contract depends on.
     */
    private ChainSummaryProbe fetchNodeCounts(RestClient client, String managementUrl, String remoteChainKey) {
        try {
            List<ChainSummaryProbe> summaries = client.get()
                    .uri("/api/chains")
                    .headers(headers -> credentials.bearerFor(managementUrl).ifPresent(headers::setBearerAuth))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ChainSummaryProbe>>() {});
            if (summaries == null) {
                return null;
            }
            return summaries.stream()
                    .filter(s -> remoteChainKey.equals(s.chainKey()))
                    .findFirst()
                    .orElse(null);
        } catch (RestClientException e) {
            return null;
        }
    }

    private ProbeAttempt failed(String managementUrl, String remoteChainKey, RestClientException e) {
        negativeCacheExpiry.put(managementUrl, Instant.now().plus(NEGATIVE_CACHE_TTL));
        recordProbeFailure(managementUrl, "unreachable");
        // Never log e.getMessage() here: for a RestClientResponseException (the common case — a
        // real third-party RPC endpoint whose URL happens to end .../<segment>/rpc, see
        // NEGATIVE_CACHE_TTL's javadoc) it embeds the full response body, which for a 404 from a
        // public API can be a multi-KB HTML page — logged once per failing node per redetect tick,
        // that dominates the log. Status line only.
        String reason = e instanceof RestClientResponseException responseException
                ? responseException.getStatusCode() + " " + responseException.getStatusText()
                : e.getClass().getSimpleName();
        log.warn("Failed to probe chaincache capabilities at {} for chain={}: {}",
                managementUrl, remoteChainKey, reason);
        return ProbeAttempt.empty(false);
    }

}
