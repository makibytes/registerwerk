package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChaincacheStreamStatus;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.finality.api.QuarantineTrigger;
import de.makibytes.registerwerk.indexer.api.TypedReorgCompensationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * A second {@link BlockFinalityFeed} producer, alongside {@code indexer.internal.ReorgGuard}'s
 * poll-based RPC probing: for every {@link ChainConfig} that opted into
 * {@link ChainConfig.FinalitySource#CHAINCACHE}, subscribes to that chain's PROVISIONAL, SAFE, and
 * FINALIZED durable event streams on its {@link RpcNode.NodeKind#CHAINCACHE} node via
 * {@code chaincache_subscribeDurable} over the same WebSocket chaincache already serves
 * {@code eth_subscribe} on, and feeds every {@code BLOCK} event into
 * {@link BlockFinalityFeed#recordObservation} and typed {@code REORG} episodes into
 * {@link BlockFinalityFeed#recordReorg}. Legacy retractions remain readable for older Chaincache
 * versions, but a retraction carrying a typed episode id is never applied a second time.
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
class ChaincacheDurableStreamManager implements ChaincacheStreamStatus {

    private static final Logger log = LoggerFactory.getLogger(ChaincacheDurableStreamManager.class);

    private final ChainConfigRepository chainConfigRepository;
    private final RpcNodeRepository rpcNodeRepository;
    private final BlockFinalityFeed blockFinalityFeed;
    private final ChaincacheReorgCoordinator reorgCoordinator;
    private final ObjectMapper objectMapper;
    private final ChaincacheCredentials credentials;
    private final String instanceId;
    private final HttpClient httpClient;
    private final MeterRegistry meterRegistry;
    private final long subscribeResponseTimeoutMs;
    private final long ackResponseTimeoutMs;

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

    ChaincacheDurableStreamManager(ChainConfigRepository chainConfigRepository, RpcNodeRepository rpcNodeRepository,
            BlockFinalityFeed blockFinalityFeed,
            ChaincacheReorgCoordinator reorgCoordinator,
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
        this.blockFinalityFeed = blockFinalityFeed;
        this.reorgCoordinator = reorgCoordinator;
        this.objectMapper = objectMapper;
        this.credentials = credentials;
        this.instanceId = instanceId;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
        this.subscribeResponseTimeoutMs = Math.max(1L, subscribeResponseTimeoutMs);
        this.ackResponseTimeoutMs = Math.max(1L, ackResponseTimeoutMs);
        this.meterRegistry = meterRegistry;
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
        List<ChainConfig> chaincacheChains =
                chainConfigRepository.findByEnabledTrueAndFinalitySource(ChainConfig.FinalitySource.CHAINCACHE);
        Map<UUID, ChainConfig> stillWanted = new java.util.HashMap<>();
        Map<UUID, List<RpcNode>> candidatesByChain = new java.util.HashMap<>();
        Map<UUID, String> durabilityDomainByChain = new java.util.HashMap<>();
        for (ChainConfig chain : chaincacheChains) {
            List<RpcNode> nodes = rpcNodeRepository.findByChainConfig_IdAndKindAndEnabledTrue(
                    chain.getId(), RpcNode.NodeKind.CHAINCACHE);
            java.util.Optional<String> durabilityDomain = sharedDurabilityDomain(nodes);
            if (!nodes.isEmpty() && durabilityDomain.isPresent()) {
                stillWanted.put(chain.getId(), chain);
                candidatesByChain.put(chain.getId(), nodes);
                durabilityDomainByChain.put(chain.getId(), durabilityDomain.orElseThrow());
            } else if (!nodes.isEmpty()) {
                log.error("Refusing chaincache durable stream for chain={}: every enabled candidate must expose "
                        + "the same nonblank durabilityDomainId", chain.getId());
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
     * One WebSocket connection per chain, subscribing to the PROVISIONAL, SAFE, and FINALIZED
     * durable streams (each block appears once per stream it reaches — see chaincache's
     * {@code CanonicalChainServiceImpl}). Buffers text frames until {@code last=true}: the JDK
     * {@link WebSocket.Listener} contract may deliver one logical message across multiple
     * {@code onText} calls.
     */
    final class ChainSubscription implements WebSocket.Listener {

        private final UUID chainId;
        private final String consumerId;
        private final String remoteChainKey;
        private final UUID nodeId;
        private final String durabilityDomainId;
        private final StringBuilder buffer = new StringBuilder();
        private final AtomicLong requestIdSeq = new AtomicLong(1);
        /** Our own outgoing JSON-RPC request id -> which stream we asked to subscribe to. */
        private final Map<Long, FinalityLevel> pendingSubscribeRequests = new ConcurrentHashMap<>();
        /** chaincache's local subscription id (returned in the subscribe result) -> which stream. */
        private final Map<String, FinalityLevel> subscriptionLevels = new ConcurrentHashMap<>();
        /** ACK request id -> cursor write awaiting a successful JSON-RPC response. */
        private final Map<Long, PendingAck> pendingAckRequests = new ConcurrentHashMap<>();
        /** At most one cursor write may be in flight per stream. */
        private final Map<FinalityLevel, Long> pendingAckByStream = new ConcurrentHashMap<>();
        /** Notifications already received for a stream whose preceding ACK is still in flight. */
        private final Map<FinalityLevel, java.util.Deque<QueuedEvent>> queuedEvents = new ConcurrentHashMap<>();
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
            // PROVISIONAL matters as much as SAFE/FINALIZED here, not just extra volume: chaincache
            // only ever appends a RETRACTION to a stream the orphaned block had actually reached
            // (CanonicalChainServiceImpl.appendRetraction always writes PROVISIONAL, and additionally
            // SAFE only if the block got that far — never both unconditionally). A shallow reorg of a
            // still-PROVISIONAL block — the common case for a chain that hasn't yet accumulated
            // safeConfirmations of depth — would otherwise never reach this subscriber at all: proven
            // live by an anvil_reorg drill against this exact code path, where a 3-block reorg of
            // not-yet-SAFE blocks produced zero durable events on the SAFE/FINALIZED-only subscription
            // this used to be.
            subscribe(webSocket, FinalityLevel.PROVISIONAL);
            subscribe(webSocket, FinalityLevel.SAFE);
            subscribe(webSocket, FinalityLevel.FINALIZED);
            webSocket.request(1);
        }

        private void subscribe(WebSocket webSocket, FinalityLevel level) {
            long requestId = requestIdSeq.getAndIncrement();
            pendingSubscribeRequests.put(requestId, level);
            Map<String, Object> params = Map.of(
                    "consumerId", consumerId,
                    "stream", level.name());
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "id", requestId,
                    "method", "chaincache_subscribeDurable",
                    "params", List.of(params));
            sendText(webSocket, objectMapper.writeValueAsString(request), "subscribe " + level);
            scheduleResponseDeadline(webSocket, requestId, true, subscribeResponseTimeoutMs);
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
                FinalityLevel level = pendingSubscribeRequests.get(requestId);
                if (level != null) {
                    JsonNode result = node.get("result");
                    if (result == null || !result.isObject()) {
                        throw new IllegalArgumentException("Subscribe response has no result object");
                    }
                    String localSubscriptionId = requiredText(result, "subscription");
                    String returnedStream = requiredText(result, "stream");
                    if (!level.name().equals(returnedStream)) {
                        throw new IllegalArgumentException("Subscribe response stream does not match request");
                    }
                    JsonNode returnedConsumer = result.get("consumerId");
                    if (returnedConsumer != null
                            && (!returnedConsumer.isTextual() || !consumerId.equals(returnedConsumer.asText()))) {
                        throw new IllegalArgumentException("Subscribe response consumerId does not match request");
                    }
                    FinalityLevel prior = subscriptionLevels.putIfAbsent(localSubscriptionId, level);
                    if (prior != null && prior != level) {
                        throw new IllegalArgumentException("Subscription id was reused for another stream");
                    }
                    pendingSubscribeRequests.remove(requestId);
                    if (pendingSubscribeRequests.isEmpty() && subscriptionLevels.size() == 3
                            && activeByChain.get(chainId) == this) {
                        connectedGauge(chainId).set(true);
                    }
                    return;
                }
                PendingAck ack = pendingAckRequests.remove(requestId);
                if (ack != null) {
                    JsonNode result = node.get("result");
                    if (result == null || !result.isBoolean() || !result.asBoolean()) {
                        throw new IllegalArgumentException("ACK response did not confirm cursor persistence");
                    }
                    if (!pendingAckByStream.remove(ack.level(), requestId)) {
                        throw new IllegalStateException("ACK response does not match the in-flight stream cursor");
                    }
                    java.util.Deque<QueuedEvent> queue = queuedEvents.get(ack.level());
                    QueuedEvent next = queue == null ? null : queue.pollFirst();
                    if (next != null) {
                        handleEvent(webSocket, next.subscriptionId(), ack.level(), next.event());
                    }
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
            FinalityLevel level = subscriptionLevels.get(localSubscriptionId);
            if (level == null) {
                throw new IllegalArgumentException("Durable event references an unknown subscription");
            }
            JsonNode event = params.path("result");
            if (pendingAckByStream.containsKey(level)) {
                java.util.Deque<QueuedEvent> queue = queuedEvents.computeIfAbsent(
                        level, ignored -> new java.util.ArrayDeque<>());
                if (queue.size() >= 1_024) {
                    throw new IllegalStateException("Durable event queue exceeded the per-stream safety limit");
                }
                queue.addLast(new QueuedEvent(localSubscriptionId, event.deepCopy()));
                return;
            }
            handleEvent(webSocket, localSubscriptionId, level, event);
        }

        private void handleEvent(WebSocket webSocket, String subscriptionId, FinalityLevel level, JsonNode event) {
            if (!event.isObject()) {
                throw new IllegalArgumentException("Durable event result must be an object");
            }
            JsonNode sequenceNode = event.get("sequence");
            if (sequenceNode == null || !sequenceNode.isIntegralNumber()
                    || !sequenceNode.canConvertToLong() || sequenceNode.asLong() < 0) {
                throw new IllegalArgumentException("Durable event sequence must be a non-negative integer");
            }
            String eventStream = event.path("stream").asText(null);
            if (!level.name().equals(eventStream)) {
                throw new IllegalArgumentException("Durable event stream does not match its subscription");
            }
            String kind = event.path("kind").asText(null);
            long sequence = sequenceNode.asLong();

            Counter.builder("registerwerk_chaincache_stream_events_total")
                    .tags("chain", chainId.toString(), "stream", level.name(), "kind", String.valueOf(kind))
                    .register(meterRegistry)
                    .increment();
            lastEventGauge(chainId).set(Instant.now().getEpochSecond());

            switch (String.valueOf(kind)) {
                case "REORG" -> handleReorg(event);
                case "RETRACTION" -> {
                    long blockNumber = requiredNonNegativeLong(event, "blockNumber");
                    requiredText(event, "retractsEventId");
                    handleRetraction(event, blockNumber);
                }
                case "BLOCK" -> blockFinalityFeed.recordObservation(
                        chainId,
                        requiredNonNegativeLong(event, "blockNumber"),
                        requiredText(event, "blockHash"),
                        level);
                case "LOG" -> {
                    // Registerwerk's finality ledger is block-scoped. LOG remains a deliberate
                    // no-op, but it is a known durable kind and therefore safe to acknowledge.
                }
                default -> throw new IllegalArgumentException("Unsupported durable event kind: " + kind);
            }
            acknowledge(webSocket, subscriptionId, level, sequence);
        }

        private void handleReorg(JsonNode event) {
            JsonNode reorg = event.path("reorg");
            if (reorg.isMissingNode() || reorg.isNull() || !reorg.isObject()) {
                throw new IllegalArgumentException("REORG durable event has no typed reorg envelope");
            }
            String schemaVersion = requiredText(reorg, "schemaVersion");
            String reorgId = requiredText(reorg, "reorgId");
            String envelopeChainKey = requiredText(reorg.path("chainKey"), "value");
            if (!remoteChainKey.equals(envelopeChainKey)) {
                throw new IllegalArgumentException("REORG envelope chainKey does not match subscribed remote chain");
            }
            ReorgObservation.ReorgSeverity severity = ReorgObservation.ReorgSeverity.valueOf(
                    requiredText(reorg, "severity"));
            ReorgObservation.BlockReference commonAncestor = reorg.path("commonAncestor").isNull()
                    || reorg.path("commonAncestor").isMissingNode()
                    ? null
                    : blockReference(reorg.path("commonAncestor"));
            List<ReorgObservation.BlockReference> orphaned = blockReferences(reorg.path("orphanedLineage"));
            List<ReorgObservation.BlockReference> replacements = blockReferences(reorg.path("replacementLineage"));
            Instant observedAt = Instant.parse(requiredText(reorg, "observedAt"));

            ReorgObservation observation = new ReorgObservation(
                    schemaVersion, reorgId, severity, commonAncestor, orphaned, replacements, observedAt);
            try {
                reorgCoordinator.apply(chainId, observation);
            } catch (TypedReorgCompensationException failedIndexerCompensation) {
                // The coordinator transaction rolled every indexer mutation back. Persist the
                // incident in a fresh finality transaction, then ACK without a poison hot-loop.
                blockFinalityFeed.recordReorg(chainId, observation, 0,
                        QuarantineTrigger.INDEXER_COMPENSATION_FAILED);
            }
        }

        private static List<ReorgObservation.BlockReference> blockReferences(JsonNode array) {
            if (!array.isArray()) {
                throw new IllegalArgumentException("Reorg lineage must be an array");
            }
            java.util.ArrayList<ReorgObservation.BlockReference> blocks = new java.util.ArrayList<>();
            array.forEach(node -> blocks.add(blockReference(node)));
            return List.copyOf(blocks);
        }

        private static ReorgObservation.BlockReference blockReference(JsonNode node) {
            if (!node.isObject()) {
                throw new IllegalArgumentException("Malformed reorg block reference");
            }
            return new ReorgObservation.BlockReference(
                    requiredNonNegativeLong(node, "blockNumber"),
                    requiredText(node, "blockHash"),
                    requiredText(node, "parentHash"),
                    FinalityLevel.valueOf(requiredText(node, "finality")));
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

        private static long requiredNonNegativeLong(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
                throw new IllegalArgumentException("Durable event " + field + " must be a non-negative integer");
            }
            return value.asLong();
        }

        /**
         * A RETRACTION event's top-level {@code blockHash} is the ORPHANED block's own hash, not
         * a replacement — chaincache's {@code CanonicalChainServiceImpl.appendRetraction} puts the
         * real correction (the common-ancestor height and the new tip's hash) in {@code payload},
         * exactly matching {@link de.makibytes.registerwerk.finality.api.BlockFinalityFeed#recordRetraction}'s
         * "everything at or after {@code forkBlockNumber} is orphaned" semantics — passing the
         * orphaned block's own hash there (as this class used to) would have recorded the WRONG
         * block as the replacement.
         *
         * <p>chaincache emits one RETRACTION event per orphaned block, plus a separate one per
         * orphaned LOG event (distinguishable by {@code retractsEventId}: {@code "block:..."} vs
         * {@code "log:..."}) — only the block-level ones matter here, since this feed tracks
         * canonical block state, not individual logs; a log-level retraction is silently
         * (correctly) skipped, still acknowledged so chaincache doesn't keep redelivering it.
         */
        private void handleRetraction(JsonNode event, long orphanedBlockNumber) {
            String retractsEventId = event.path("retractsEventId").asText(null);
            if (retractsEventId == null || !retractsEventId.startsWith("block:")) {
                return;
            }
            JsonNode payload = event.path("payload");
            // New Chaincache versions retain per-block/per-log RETRACTIONs for generic Ethereum
            // filter consumers after emitting one typed REORG episode. Applying both is unsafe:
            // a replayed height-range retraction could orphan the already-installed replacement.
            if (payload.hasNonNull("reorgId") && !payload.path("reorgId").asText().isBlank()) {
                return;
            }
            long commonAncestor = payload.path("commonAncestor").asLong(orphanedBlockNumber - 1);
            // payload.replacementBlockHash is the reorg's new TIP hash, not necessarily the hash
            // now sitting at commonAncestor+1 (they coincide only for a 1-block-deep reorg) —
            // recordRetraction's contract wants specifically the latter ("the freshly-observed
            // canonical hash AT forkBlockNumber"), which this event doesn't carry. Passing the tip
            // hash here would misrecord it as the fork-height hash for any deeper reorg; null is
            // the documented, honest "not available" case (see the interface javadoc) — the next
            // BLOCK event chaincache pushes for that height (chaincache always re-announces the
            // new canonical block after a retraction) supplies the real hash instead.
            blockFinalityFeed.recordRetraction(chainId, commonAncestor + 1, null, 0);
        }

        private void acknowledge(WebSocket webSocket, String subscriptionId, FinalityLevel level, long sequence) {
            long requestId = requestIdSeq.getAndIncrement();
            if (pendingAckByStream.putIfAbsent(level, requestId) != null) {
                throw new IllegalStateException("Attempted a second ACK before the preceding cursor was confirmed");
            }
            pendingAckRequests.put(requestId, new PendingAck(level, sequence));
            Map<String, Object> params = Map.of(
                    "subscription", subscriptionId,
                    "consumerId", consumerId,
                    "stream", level.name(),
                    "sequence", sequence);
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "id", requestId,
                    "method", "chaincache_ack",
                    "params", List.of(params));
            sendText(webSocket, objectMapper.writeValueAsString(request), "acknowledge " + level);
            scheduleResponseDeadline(webSocket, requestId, false, ackResponseTimeoutMs);
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

        private void scheduleResponseDeadline(WebSocket webSocket, long requestId, boolean subscribe,
                long timeoutMs) {
            CompletableFuture.runAsync(() -> {
                boolean stillPending = subscribe
                        ? pendingSubscribeRequests.containsKey(requestId)
                        : pendingAckRequests.containsKey(requestId);
                if (stillPending) {
                    failStop(webSocket, new IllegalStateException(
                            (subscribe ? "Subscribe" : "ACK") + " response deadline exceeded"));
                }
            }, CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS));
        }

        private record PendingAck(FinalityLevel level, long sequence) {}

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
