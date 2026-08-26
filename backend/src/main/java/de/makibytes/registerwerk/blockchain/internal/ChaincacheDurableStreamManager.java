package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChaincacheStreamStatus;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A second finality-feed producer, alongside {@code indexer.internal.ReorgGuard}'s poll-based RPC
 * probing: for every {@link ChainConfig} that opted into {@link ChainConfig.FinalitySource#CHAINCACHE},
 * subscribes to that chain's unified lifecycle-v2 stream on its {@link RpcNode.NodeKind#CHAINCACHE}
 * node via {@code chaincache_subscribeLifecycle} over the same WebSocket chaincache already serves
 * {@code eth_subscribe} on. Lifecycle events are transactionally projected into Registerwerk's
 * inbox, finality ledger, and smart-contract projections (all inside {@link ChaincacheLifecycleEventProcessor})
 * before their cursor is acknowledged.
 *
 * <p>This is possible only because {@code BlockFinalityFeed} already inverted the dependency
 * (indexer/blockchain call into {@code finality}, not the reverse) — the RPC-probing path in
 * {@code ReorgGuard} is completely untouched; a chain simply has one producer or the other
 * depending on {@link ChainConfig#getFinalitySource()}.
 *
 * <p>Delivery is push-based and gap-free (chaincache's durable outbox, not a poll), which is
 * exactly the capability gap the portfolio plan calls out as the showcase: a direct RPC connection
 * can miss a short-lived reorg between polls and has no real SAFE tier without a {@code safe}
 * block tag; this feed cannot miss one and always has both tiers.
 *
 * <p>Uses the JDK's built-in {@link HttpClient#newWebSocketBuilder()} rather than pulling in a
 * Spring WebSocket client dependency for a single outbound connection per chain.
 */
@Component
class ChaincacheDurableStreamManager implements ChaincacheStreamStatus, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ChaincacheDurableStreamManager.class);

    private final ChainConfigRepository chainConfigRepository;
    private final RpcNodeRepository rpcNodeRepository;
    private final ChaincacheLifecycleEventProcessor lifecycleProcessor;
    private final ChaincacheLifecycleFailureRecorder lifecycleFailureRecorder;
    private final ObjectMapper objectMapper;
    private final ChaincacheCredentials credentials;
    private final String instanceId;
    private final HttpClient httpClient;
    private final MeterRegistry meterRegistry;
    private final long subscribeResponseTimeoutMs;
    private final long ackResponseTimeoutMs;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Map<UUID, ChainSubscription> activeByChain = new ConcurrentHashMap<>();
    /** chainId -> the node id whose connection attempt most recently failed, so the next tick's
     *  {@link #selectNode} excludes it in favor of a healthy sibling instead of retrying it
     *  immediately. Cleared at the end of every {@link #reconcile()} tick — see that method. */
    private final Map<UUID, UUID> recentlyFailedNodeId = new ConcurrentHashMap<>();
    /** Backing state for the per-chain {@code registerwerk_chaincache_stream_connected} gauge —
     *  Micrometer gauges track a referenced mutable object, so these must outlive individual
     *  {@link ChainSubscription} instances across reconnects. Tagged by {@code chainId} (not the
     *  human-readable {@code ChainConfig} identifier) since that's the only handle available at
     *  every call site that touches these without an extra repository lookup. */
    private final Map<UUID, AtomicBoolean> connectedByChain = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> lastEventEpochSecondByChain = new ConcurrentHashMap<>();

    @Autowired
    ChaincacheDurableStreamManager(ChainConfigRepository chainConfigRepository, RpcNodeRepository rpcNodeRepository,
            ChaincacheLifecycleEventProcessor lifecycleProcessor,
            ChaincacheLifecycleFailureRecorder lifecycleFailureRecorder,
            ObjectMapper objectMapper,
            ChaincacheCredentials credentials,
            @Value("${registerwerk.chaincache.instance-id:registerwerk}") String instanceId,
            @Value("${registerwerk.chaincache.stream.connect-timeout-ms:10000}") long connectTimeoutMs,
            @Value("${registerwerk.chaincache.stream.subscribe-response-timeout-ms:10000}")
            long subscribeResponseTimeoutMs,
            @Value("${registerwerk.chaincache.stream.ack-response-timeout-ms:10000}")
            long ackResponseTimeoutMs,
            MeterRegistry meterRegistry) {
        this.chainConfigRepository = chainConfigRepository;
        this.rpcNodeRepository = rpcNodeRepository;
        this.lifecycleProcessor = java.util.Objects.requireNonNull(lifecycleProcessor, "lifecycleProcessor");
        this.lifecycleFailureRecorder =
                java.util.Objects.requireNonNull(lifecycleFailureRecorder, "lifecycleFailureRecorder");
        this.objectMapper = objectMapper;
        this.credentials = credentials;
        this.instanceId = instanceId;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
        this.subscribeResponseTimeoutMs = Math.max(1L, subscribeResponseTimeoutMs);
        this.ackResponseTimeoutMs = Math.max(1L, ackResponseTimeoutMs);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        activeByChain.values().forEach(ChainSubscription::close);
        activeByChain.clear();
        connectedByChain.values().forEach(state -> state.set(false));
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    /** Deliberately reads {@link #connectedByChain} directly rather than going through
     *  {@link #connectedGauge}: that method's {@code computeIfAbsent} would register a permanent
     *  Micrometer gauge for every chain ever passed here, including ones that don't use chaincache
     *  at all — this is called from {@code RpcNodeService.toResponse} for every node, not just
     *  chaincache ones. */
    @Override
    public boolean isConnected(UUID chainConfigId) {
        AtomicBoolean state = connectedByChain.get(chainConfigId);
        return state != null && state.get();
    }

    private AtomicBoolean connectedGauge(UUID chainId) {
        return connectedByChain.computeIfAbsent(chainId, id -> meterRegistry.gauge(
                "registerwerk_chaincache_stream_connected", Tags.of("chain", id.toString()),
                new AtomicBoolean(false), ab -> ab.get() ? 1.0 : 0.0));
    }

    private AtomicLong lastEventGauge(UUID chainId) {
        return lastEventEpochSecondByChain.computeIfAbsent(chainId, id -> meterRegistry.gauge(
                "registerwerk_chaincache_stream_last_event_timestamp_seconds", Tags.of("chain", id.toString()),
                new AtomicLong(0), AtomicLong::get));
    }

    /**
     * Opens a connection for every qualifying chain that doesn't already have a live one, and
     * closes any connection whose chain no longer qualifies (disabled, reverted to
     * {@code RPC_SELF_PROBE}, or its chaincache node was disabled/removed) — the reconnect path
     * for a dropped connection is simply "it's no longer in {@link #activeByChain}, so the next
     * tick opens a fresh one", rather than separate retry/backoff bookkeeping.
     *
     * <p>Deliberately NOT {@code @SchedulerLock}'d: every replica maintains its own WebSocket, but
     * all use the same stable logical {@link #instanceId}. Chaincache's database-backed consumer
     * lease fences that identity so exactly one connection owns the cursor and sibling replicas
     * remain retrying standbys. This keeps cursor identity stable through a complete pod rollout
     * without putting JVM-local connection state behind a short-lived scheduler lock.
     */
    @Scheduled(fixedDelayString = "${registerwerk.chaincache.stream.reconcile-interval-ms:30000}",
               initialDelayString = "${registerwerk.chaincache.stream.reconcile-initial-delay-ms:20000}")
    void reconcile() {
        if (!running.get()) {
            return;
        }
        List<ChainConfig> chaincacheChains =
                chainConfigRepository.findByEnabledTrueAndFinalitySource(ChainConfig.FinalitySource.CHAINCACHE);
        Map<UUID, ChainConfig> stillWanted = new java.util.HashMap<>();
        Map<UUID, List<RpcNode>> candidatesByChain = new java.util.HashMap<>();
        Map<UUID, String> durabilityDomainByChain = new java.util.HashMap<>();
        for (ChainConfig chain : chaincacheChains) {
            List<RpcNode> nodes = rpcNodeRepository.findByChainConfig_IdAndKindAndEnabledTrue(
                    chain.getId(), RpcNode.NodeKind.CHAINCACHE);
            java.util.Optional<String> durabilityDomain = sharedDurabilityDomain(nodes);
            boolean lifecycleV2 = nodes.stream().allMatch(ChaincacheDurableStreamManager::supportsLifecycleV2);
            if (!nodes.isEmpty() && durabilityDomain.isPresent() && lifecycleV2) {
                stillWanted.put(chain.getId(), chain);
                candidatesByChain.put(chain.getId(), nodes);
                durabilityDomainByChain.put(chain.getId(), durabilityDomain.orElseThrow());
            } else if (!nodes.isEmpty()) {
                log.error("Refusing chaincache durable stream for chain={}: every enabled candidate must expose "
                        + "the same nonblank durabilityDomainId and durableProtocolVersion=2", chain.getId());
            }
        }

        activeByChain.keySet().removeIf(chainId -> {
            ChainSubscription active = activeByChain.get(chainId);
            List<RpcNode> candidates = candidatesByChain.get(chainId);
            String durabilityDomain = durabilityDomainByChain.get(chainId);
            boolean selectedNodeStillEligible = active != null && candidates != null
                    && candidates.stream().anyMatch(node -> node.getId().equals(active.nodeId));
            boolean sameDurabilityDomain = active != null && durabilityDomain != null
                    && durabilityDomain.equals(active.durabilityDomainId);
            if (!stillWanted.containsKey(chainId) || !selectedNodeStillEligible || !sameDurabilityDomain) {
                if (active != null) {
                    active.close();
                }
                log.info("Closed chaincache durable stream for chain={} (node or durability domain no longer "
                        + "qualifies)", chainId);
                return true;
            }
            return false;
        });

        for (Map.Entry<UUID, ChainConfig> entry : stillWanted.entrySet()) {
            UUID chainId = entry.getKey();
            ChainSubscription existing = activeByChain.get(chainId);
            if (existing != null && existing.isOpen()) {
                continue;
            }
            List<RpcNode> nodes = candidatesByChain.get(chainId);
            String durabilityDomain = durabilityDomainByChain.get(chainId);
            selectNode(nodes, recentlyFailedNodeId.get(chainId))
                    .ifPresent(node -> connect(chainId, node, durabilityDomain));
        }
        // A node excluded for this one tick (see connect()'s failure callback) has now had its
        // chance to be skipped in favor of a sibling; let it be a candidate again next tick.
        recentlyFailedNodeId.clear();
    }

    /**
     * Deterministic choice among a chain's enabled chaincache nodes — was {@code nodes.get(0)}
     * (arbitrary; a comment on the repository method this came from rationalized it as "any one is
     * usable", true only when there is genuinely one chaincache instance behind every such node —
     * under the per-chain-workload model two CHAINCACHE-kind nodes for the same chain are two
     * independent chaincache workloads with two independent durable-event outboxes, so which one
     * gets connected is a real choice, not an arbitrary one). Prefers a healthy node with the
     * lowest {@code lagFromBest}, tie-broken by the oldest {@code createdAt} for stability (so this
     * doesn't flap between two equally-healthy nodes from tick to tick); excludes whichever node
     * failed to connect on the immediately preceding tick so a genuine failover to a sibling can
     * happen instead of retrying the same broken node forever.
     */
    static java.util.Optional<RpcNode> selectNode(List<RpcNode> nodes, UUID excludeNodeId) {
        List<RpcNode> candidates = nodes.stream()
                .filter(n -> !n.getId().equals(excludeNodeId))
                .toList();
        if (candidates.isEmpty()) {
            candidates = nodes;
        }
        return candidates.stream().min(
                Comparator.comparing((RpcNode n) -> !n.isHealthy())
                        .thenComparing(n -> n.getLagFromBest() != null ? n.getLagFromBest() : Integer.MAX_VALUE)
                        .thenComparing(RpcNode::getCreatedAt));
    }

    /** A cursor can resume through another endpoint only when every endpoint is backed by the
     * same durable outbox. Missing or conflicting capability metadata therefore disables automatic
     * consumption instead of silently starting a new cursor in an unrelated database. */
    static java.util.Optional<String> sharedDurabilityDomain(List<RpcNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return java.util.Optional.empty();
        }
        String expected = null;
        for (RpcNode node : nodes) {
            Object raw = node.getCapabilities() == null
                    ? null
                    : node.getCapabilities().get("durabilityDomainId");
            if (!(raw instanceof String value) || value.isBlank()) {
                return java.util.Optional.empty();
            }
            String normalized = value.trim();
            if (expected == null) {
                expected = normalized;
            } else if (!expected.equals(normalized)) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.of(expected);
    }

    private static boolean supportsLifecycleV2(RpcNode node) {
        Object value = node.getCapabilities() == null ? null
                : node.getCapabilities().get("durableProtocolVersion");
        return "2".equals(String.valueOf(value));
    }

    private void connect(UUID chainId, RpcNode node, String durabilityDomainId) {
        String wsUrl = toWebSocketUrl(node.getManagementUrl(), node.getRemoteChainKey());
        if (wsUrl == null) {
            log.warn("chaincache node id={} for chain={} has no usable managementUrl/remoteChainKey; skipping",
                    node.getId(), chainId);
            return;
        }
        // instanceId is a stable logical consumer group, not a pod identity. Chaincache's durable
        // consumer lease elects one Registerwerk replica while the others retry as warm standbys;
        // every replacement replica therefore resumes the same persisted cursor.
        String consumerId = "registerwerk:" + instanceId + ":" + node.getRemoteChainKey();
        ChainSubscription subscription = new ChainSubscription(
                chainId, consumerId, node.getRemoteChainKey(), node.getId(), durabilityDomainId);
        activeByChain.put(chainId, subscription);
        WebSocket.Builder wsBuilder = httpClient.newWebSocketBuilder();
        // "Authorization" is not on the JDK WebSocket API's restricted-header list, so this works
        // on the handshake — needed the moment an operator sets chaincache.auth.rpc-enabled=true
        // (off by default; chaincache's /{chain}/rpc and /{chain}/ws stay open otherwise).
        credentials.bearerFor(node.getManagementUrl())
                .ifPresent(token -> wsBuilder.header("Authorization", "Bearer " + token));
        wsBuilder.buildAsync(URI.create(wsUrl), subscription)
                .whenComplete((ws, failure) -> {
                    if (failure != null) {
                        recentlyFailedNodeId.put(chainId, node.getId());
                        log.warn("Failed to open chaincache durable stream for chain={} at {}: {}",
                                chainId, wsUrl, failure.getMessage());
                        if (activeByChain.remove(chainId, subscription)) {
                            connectedGauge(chainId).set(false);
                        }
                    }
                });
    }

    private static String toWebSocketUrl(String managementUrl, String remoteChainKey) {
        if (managementUrl == null || managementUrl.isBlank() || remoteChainKey == null || remoteChainKey.isBlank()) {
            return null;
        }
        String ws = managementUrl.startsWith("https://") ? "wss://" + managementUrl.substring("https://".length())
                : managementUrl.startsWith("http://") ? "ws://" + managementUrl.substring("http://".length())
                : managementUrl;
        if (ws.endsWith("/")) {
            ws = ws.substring(0, ws.length() - 1);
        }
        return ws + "/" + remoteChainKey + "/ws";
    }

    /**
     * One WebSocket connection per chain, subscribing to the unified lifecycle-v2 stream.
     * Buffers text frames until {@code last=true}: the JDK {@link WebSocket.Listener} contract may
     * deliver one logical message across multiple {@code onText} calls.
     */
    final class ChainSubscription implements WebSocket.Listener {

        private final UUID chainId;
        private final String consumerId;
        private final String remoteChainKey;
        private final UUID nodeId;
        private final String durabilityDomainId;
        private final StringBuilder buffer = new StringBuilder();
        private final AtomicLong requestIdSeq = new AtomicLong(1);
        private volatile Long pendingLifecycleSubscribeRequest;
        private volatile String lifecycleSubscriptionId;
        private volatile Long pendingLifecycleAckRequest;
        private final java.util.Deque<QueuedEvent> queuedLifecycleEvents = new java.util.ArrayDeque<>();
        private volatile WebSocket webSocket;
        private volatile boolean closed = false;

        ChainSubscription(UUID chainId, String consumerId, String remoteChainKey,
                UUID nodeId, String durabilityDomainId) {
            this.chainId = chainId;
            this.consumerId = consumerId;
            this.remoteChainKey = remoteChainKey;
            this.nodeId = nodeId;
            this.durabilityDomainId = durabilityDomainId;
        }

        boolean isOpen() {
            // A buildAsync handshake already installed in activeByChain is an active attempt.
            // Treating it as closed until onOpen arrives lets every reconcile tick start another
            // socket for the same stable consumer, racing its exclusive Chaincache lease.
            return !closed && (webSocket != null || activeByChain.get(chainId) == this);
        }

        void close() {
            closed = true;
            if (activeByChain.get(chainId) == this) {
                connectedGauge(chainId).set(false);
            }
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "no longer needed").exceptionally(e -> null);
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.webSocket = webSocket;
            log.info("chaincache durable stream connected for chain={}, consumerId={}", chainId, consumerId);
            if (activeByChain.get(chainId) == this) {
                connectedGauge(chainId).set(false);
            }
            subscribeLifecycle(webSocket);
            webSocket.request(1);
        }

        private void subscribeLifecycle(WebSocket webSocket) {
            long requestId = requestIdSeq.getAndIncrement();
            pendingLifecycleSubscribeRequest = requestId;
            Map<String, Object> params = Map.of(
                    "consumerId", consumerId,
                    "startBlock", lifecycleProcessor.earliestDeploymentBlock(chainId));
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "id", requestId,
                    "method", "chaincache_subscribeLifecycle",
                    "params", List.of(params));
            sendText(webSocket, objectMapper.writeValueAsString(request), "subscribe lifecycle");
            scheduleResponseDeadline(webSocket, requestId, true);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (closed) {
                return null;
            }
            buffer.append(data);
            if (!last) {
                webSocket.request(1);
                return null;
            }
            String message = buffer.toString();
            buffer.setLength(0);
            try {
                handleMessage(webSocket, objectMapper.readTree(message));
                webSocket.request(1);
            } catch (Exception e) {
                failStop(webSocket, e);
            }
            return null;
        }

        private synchronized void failStop(WebSocket webSocket, Throwable failure) {
            if (closed) {
                return;
            }
            log.error("Fail-stopping chaincache durable stream for chain={} after an unacknowledged message: {}",
                    chainId, failure.getMessage());
            closed = true;
            recentlyFailedNodeId.put(chainId, nodeId);
            if (activeByChain.remove(chainId, this)) {
                connectedGauge(chainId).set(false);
            }
            // A later ACK uses a monotonic cursor upstream; keeping this connection alive after a
            // poison event would let sequence N+1 acknowledge past failed sequence N permanently.
            webSocket.sendClose(1011, "durable event processing failed");
        }

        private void handleMessage(WebSocket webSocket, JsonNode node) {
            if (node.has("error")) {
                throw new IllegalStateException("Chaincache JSON-RPC error: " + node.path("error"));
            }
            if (node.hasNonNull("id")) {
                long requestId = node.path("id").asLong();
                if (pendingLifecycleSubscribeRequest != null
                        && pendingLifecycleSubscribeRequest.longValue() == requestId) {
                    JsonNode result = node.get("result");
                    if (result == null || !result.isObject()) {
                        throw new IllegalArgumentException("Lifecycle subscribe response has no result object");
                    }
                    String subscriptionId = requiredText(result, "subscription");
                    if (!consumerId.equals(requiredText(result, "consumerId"))
                            || !"2".equals(requiredText(result, "schemaVersion"))
                            || !durabilityDomainId.equals(requiredText(result, "durabilityDomainId"))
                            || !remoteChainKey.equals(requiredText(result, "chainKey"))) {
                        throw new IllegalArgumentException("Lifecycle subscribe response identity mismatch");
                    }
                    lifecycleSubscriptionId = subscriptionId;
                    pendingLifecycleSubscribeRequest = null;
                    if (activeByChain.get(chainId) == this) {
                        connectedGauge(chainId).set(true);
                    }
                    return;
                }
                if (pendingLifecycleAckRequest != null && pendingLifecycleAckRequest.longValue() == requestId) {
                    JsonNode result = node.get("result");
                    if (result == null || !result.isBoolean() || !result.asBoolean()) {
                        throw new IllegalArgumentException("Lifecycle ACK did not confirm cursor persistence");
                    }
                    pendingLifecycleAckRequest = null;
                    QueuedEvent next = queuedLifecycleEvents.pollFirst();
                    if (next != null) {
                        handleLifecycleEvent(webSocket, next.subscriptionId(), next.event());
                    }
                    return;
                }
                return;
            }
            String method = node.path("method").asText(null);
            if ("chaincache_subscriptionError".equals(method)) {
                throw new IllegalStateException("Chaincache durable subscription failed: "
                        + node.path("params").path("message").asText("unknown failure"));
            }
            if (!"chaincache_event".equals(method)) {
                return;
            }
            JsonNode params = node.path("params");
            String localSubscriptionId = params.path("subscription").asText(null);
            if (!java.util.Objects.equals(lifecycleSubscriptionId, localSubscriptionId)) {
                throw new IllegalArgumentException("Lifecycle event references an unknown subscription");
            }
            JsonNode event = params.path("result");
            if (pendingLifecycleAckRequest != null) {
                if (queuedLifecycleEvents.size() >= 1_024) {
                    throw new IllegalStateException("Lifecycle event queue exceeded the safety limit");
                }
                queuedLifecycleEvents.addLast(new QueuedEvent(localSubscriptionId, event.deepCopy()));
            } else {
                handleLifecycleEvent(webSocket, localSubscriptionId, event);
            }
        }

        private void handleLifecycleEvent(WebSocket webSocket, String subscriptionId, JsonNode event) {
            try {
                lifecycleProcessor.process(chainId, consumerId, remoteChainKey, durabilityDomainId, event);
            } catch (RuntimeException failure) {
                try {
                    lifecycleFailureRecorder.record(chainId, consumerId, remoteChainKey,
                            durabilityDomainId, event, failure);
                } catch (RuntimeException journalFailure) {
                    failure.addSuppressed(journalFailure);
                }
                throw failure;
            }
            String kind = event.path("kind").asText("UNKNOWN");
            String finality = event.path("finality").asText("NONE");
            Counter.builder("registerwerk_chaincache_stream_events_total")
                    .tags("chain", chainId.toString(), "stream", "LIFECYCLE",
                            "finality", finality, "kind", kind)
                    .register(meterRegistry).increment();
            lastEventGauge(chainId).set(Instant.now().getEpochSecond());
            JsonNode sequence = event.get("sequence");
            if (sequence == null || !sequence.isIntegralNumber() || !sequence.canConvertToLong()
                    || sequence.asLong() < 0) {
                throw new IllegalArgumentException("Lifecycle sequence must be a non-negative integer");
            }
            acknowledgeLifecycle(webSocket, subscriptionId, sequence.asLong());
        }

        private void acknowledgeLifecycle(WebSocket webSocket, String subscriptionId, long sequence) {
            long requestId = requestIdSeq.getAndIncrement();
            if (pendingLifecycleAckRequest != null) {
                throw new IllegalStateException("Attempted a second lifecycle ACK before confirmation");
            }
            pendingLifecycleAckRequest = requestId;
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "id", requestId,
                    "method", "chaincache_ackLifecycle",
                    "params", List.of(Map.of(
                            "subscription", subscriptionId,
                            "consumerId", consumerId,
                            "sequence", sequence)));
            sendText(webSocket, objectMapper.writeValueAsString(request), "acknowledge lifecycle");
            scheduleResponseDeadline(webSocket, requestId, false);
        }

        private static String requiredText(JsonNode node, String field) {
            JsonNode fieldNode = node.get(field);
            if (fieldNode == null || !fieldNode.isTextual()) {
                throw new IllegalArgumentException("Missing required durable field: " + field);
            }
            String value = fieldNode.textValue();
            if (value.isBlank()) {
                throw new IllegalArgumentException("Missing required durable field: " + field);
            }
            return value;
        }

        private void sendText(WebSocket webSocket, String payload, String operation) {
            CompletionStage<WebSocket> send;
            try {
                send = webSocket.sendText(payload, true);
            } catch (RuntimeException failure) {
                failStop(webSocket, new IllegalStateException(
                        "Failed to send chaincache " + operation + " request", failure));
                return;
            }
            if (send == null) {
                failStop(webSocket, new IllegalStateException(
                        "Chaincache " + operation + " send returned no completion stage"));
                return;
            }
            send.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    failStop(webSocket, new IllegalStateException(
                            "Failed to send chaincache " + operation + " request", failure));
                }
            });
        }

        private void scheduleResponseDeadline(WebSocket webSocket, long requestId, boolean subscribe) {
            long timeoutMs = subscribe ? subscribeResponseTimeoutMs : ackResponseTimeoutMs;
            CompletableFuture.runAsync(() -> {
                boolean stillPending = subscribe
                        ? java.util.Objects.equals(pendingLifecycleSubscribeRequest, requestId)
                        : java.util.Objects.equals(pendingLifecycleAckRequest, requestId);
                if (stillPending) {
                    failStop(webSocket, new IllegalStateException(
                            (subscribe ? "Subscribe" : "ACK") + " response deadline exceeded"));
                }
            }, CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS));
        }

        private record QueuedEvent(String subscriptionId, JsonNode event) {}

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("chaincache durable stream error for chain={}: {}", chainId, error.getMessage());
            closed = true;
            recentlyFailedNodeId.put(chainId, nodeId);
            if (activeByChain.remove(chainId, this)) {
                connectedGauge(chainId).set(false);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("chaincache durable stream closed for chain={}: {} {}", chainId, statusCode, reason);
            closed = true;
            if (statusCode != WebSocket.NORMAL_CLOSURE) {
                recentlyFailedNodeId.put(chainId, nodeId);
            }
            if (activeByChain.remove(chainId, this)) {
                connectedGauge(chainId).set(false);
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
