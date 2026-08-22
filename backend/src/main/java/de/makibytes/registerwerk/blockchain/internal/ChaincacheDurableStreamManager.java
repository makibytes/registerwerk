package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChaincacheStreamStatus;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A second {@link BlockFinalityFeed} producer, alongside {@code indexer.internal.ReorgGuard}'s
 * poll-based RPC probing: for every {@link ChainConfig} that opted into
 * {@link ChainConfig.FinalitySource#CHAINCACHE}, subscribes to that chain's PROVISIONAL, SAFE, and
 * FINALIZED durable event streams on its {@link RpcNode.NodeKind#CHAINCACHE} node via
 * {@code chaincache_subscribeDurable} over the same WebSocket chaincache already serves
 * {@code eth_subscribe} on, and feeds every {@code BLOCK} event into
 * {@link BlockFinalityFeed#recordObservation} and every {@code RETRACTION} event into
 * {@link BlockFinalityFeed#recordRetraction}.
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
    private final ObjectMapper objectMapper;
    private final ChaincacheCredentials credentials;
    private final String instanceId;
    private final HttpClient httpClient;
    private final MeterRegistry meterRegistry;

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
                                   BlockFinalityFeed blockFinalityFeed, ObjectMapper objectMapper,
                                   ChaincacheCredentials credentials,
                                   @Value("${registerwerk.chaincache.instance-id:registerwerk}") String instanceId,
                                   @Value("${registerwerk.chaincache.stream.connect-timeout-ms:10000}") long connectTimeoutMs,
                                   MeterRegistry meterRegistry) {
        this.chainConfigRepository = chainConfigRepository;
        this.rpcNodeRepository = rpcNodeRepository;
        this.blockFinalityFeed = blockFinalityFeed;
        this.objectMapper = objectMapper;
        this.credentials = credentials;
        this.instanceId = instanceId;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
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
     * <p>Deliberately NOT {@code @SchedulerLock}'d, unlike most of this codebase's scheduled jobs
     * (see {@code RpcNodeHealthService}'s own javadoc making the same choice for the same reason):
     * {@link #activeByChain} and each replica's WebSocket connection are this JVM's own in-memory
     * state, and {@link BlockFinalityFeed}'s contract is idempotent/monotonic (a duplicate
     * observation from two replicas both holding a live connection is harmless), so every replica
     * running this independently is correct. A cluster-wide lock would be actively wrong here: it
     * would leave every replica but the lock holder never opening a connection at all, each using
     * its own {@link #instanceId}-scoped {@code consumerId} — under a locked design only the
     * elected replica's cursor would ever advance, and the others would silently never catch up.
     */
    @Scheduled(fixedDelayString = "${registerwerk.chaincache.stream.reconcile-interval-ms:30000}",
               initialDelayString = "${registerwerk.chaincache.stream.reconcile-initial-delay-ms:20000}")
    void reconcile() {
        List<ChainConfig> chaincacheChains =
                chainConfigRepository.findByEnabledTrueAndFinalitySource(ChainConfig.FinalitySource.CHAINCACHE);
        Map<UUID, ChainConfig> stillWanted = new java.util.HashMap<>();
        for (ChainConfig chain : chaincacheChains) {
            List<RpcNode> nodes = rpcNodeRepository.findByChainConfig_IdAndKindAndEnabledTrue(
                    chain.getId(), RpcNode.NodeKind.CHAINCACHE);
            if (!nodes.isEmpty()) {
                stillWanted.put(chain.getId(), chain);
            }
        }

        activeByChain.keySet().removeIf(chainId -> {
            if (!stillWanted.containsKey(chainId)) {
                activeByChain.get(chainId).close();
                log.info("Closed chaincache durable stream for chain={} (no longer qualifies)", chainId);
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
            List<RpcNode> nodes = rpcNodeRepository.findByChainConfig_IdAndKindAndEnabledTrue(
                    chainId, RpcNode.NodeKind.CHAINCACHE);
            selectNode(nodes, recentlyFailedNodeId.get(chainId)).ifPresent(node -> connect(chainId, node));
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

    private void connect(UUID chainId, RpcNode node) {
        String wsUrl = toWebSocketUrl(node.getManagementUrl(), node.getRemoteChainKey());
        if (wsUrl == null) {
            log.warn("chaincache node id={} for chain={} has no usable managementUrl/remoteChainKey; skipping",
                    node.getId(), chainId);
            return;
        }
        // Stable across restarts of this process (so its durable-stream cursor resumes rather than
        // starting over), distinct per replica (so replicas don't share one cursor and silently
        // split the event stream between them — see reconcile()'s own javadoc) and per remote
        // chain key (a chaincache workload only ever serves one chain, but this consumerId is
        // still scoped to it for clarity in chaincache's own consumer listing).
        String consumerId = "registerwerk:" + instanceId + ":" + node.getRemoteChainKey();
        ChainSubscription subscription = new ChainSubscription(chainId, consumerId);
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
                        activeByChain.remove(chainId, subscription);
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
        private final StringBuilder buffer = new StringBuilder();
        private final AtomicLong requestIdSeq = new AtomicLong(1);
        /** Our own outgoing JSON-RPC request id -> which stream we asked to subscribe to. */
        private final Map<Long, FinalityLevel> pendingSubscribeRequests = new ConcurrentHashMap<>();
        /** chaincache's local subscription id (returned in the subscribe result) -> which stream. */
        private final Map<String, FinalityLevel> subscriptionLevels = new ConcurrentHashMap<>();
        private volatile WebSocket webSocket;
        private volatile boolean closed = false;

        ChainSubscription(UUID chainId, String consumerId) {
            this.chainId = chainId;
            this.consumerId = consumerId;
        }

        boolean isOpen() {
            return webSocket != null && !closed;
        }

        void close() {
            closed = true;
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "no longer needed").exceptionally(e -> null);
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.webSocket = webSocket;
            log.info("chaincache durable stream connected for chain={}, consumerId={}", chainId, consumerId);
            connectedGauge(chainId).set(true);
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
            webSocket.sendText(objectMapper.writeValueAsString(request), true);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            webSocket.request(1);
            if (!last) {
                return null;
            }
            String message = buffer.toString();
            buffer.setLength(0);
            try {
                handleMessage(webSocket, objectMapper.readTree(message));
            } catch (Exception e) {
                log.warn("Failed to process chaincache durable-stream message for chain={}: {}", chainId, e.getMessage());
            }
            return null;
        }

        private void handleMessage(WebSocket webSocket, JsonNode node) {
            if (node.has("id") && node.has("result") && !node.path("id").isNull()) {
                long requestId = node.path("id").asLong();
                FinalityLevel level = pendingSubscribeRequests.remove(requestId);
                if (level != null) {
                    String localSubscriptionId = node.path("result").path("subscription").asText(null);
                    if (localSubscriptionId != null) {
                        subscriptionLevels.put(localSubscriptionId, level);
                    }
                }
                return;
            }
            if (node.has("error")) {
                log.warn("chaincache durable-stream error for chain={}: {}", chainId, node.path("error"));
                return;
            }
            if (!"chaincache_event".equals(node.path("method").asText(null))) {
                return;
            }
            JsonNode params = node.path("params");
            String localSubscriptionId = params.path("subscription").asText(null);
            FinalityLevel level = subscriptionLevels.get(localSubscriptionId);
            if (level == null) {
                return;
            }
            JsonNode event = params.path("result");
            handleEvent(webSocket, level, event);
        }

        private void handleEvent(WebSocket webSocket, FinalityLevel level, JsonNode event) {
            String kind = event.path("kind").asText(null);
            Long blockNumber = event.hasNonNull("blockNumber") ? event.path("blockNumber").asLong() : null;
            long sequence = event.path("sequence").asLong();

            Counter.builder("registerwerk_chaincache_stream_events_total")
                    .tags("chain", chainId.toString(), "stream", level.name(), "kind", String.valueOf(kind))
                    .register(meterRegistry)
                    .increment();
            lastEventGauge(chainId).set(Instant.now().getEpochSecond());

            if ("RETRACTION".equals(kind) && blockNumber != null) {
                handleRetraction(event, blockNumber);
            } else if ("BLOCK".equals(kind) && blockNumber != null) {
                String blockHash = event.path("blockHash").asText(null);
                if (blockHash != null) {
                    blockFinalityFeed.recordObservation(chainId, blockNumber, blockHash, level);
                }
            }
            acknowledge(webSocket, level, sequence);
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

        private void acknowledge(WebSocket webSocket, FinalityLevel level, long sequence) {
            Map<String, Object> params = Map.of(
                    "consumerId", consumerId,
                    "stream", level.name(),
                    "sequence", sequence);
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "id", requestIdSeq.getAndIncrement(),
                    "method", "chaincache_ack",
                    "params", List.of(params));
            webSocket.sendText(objectMapper.writeValueAsString(request), true);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("chaincache durable stream error for chain={}: {}", chainId, error.getMessage());
            closed = true;
            connectedGauge(chainId).set(false);
            activeByChain.remove(chainId, this);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("chaincache durable stream closed for chain={}: {} {}", chainId, statusCode, reason);
            closed = true;
            connectedGauge(chainId).set(false);
            activeByChain.remove(chainId, this);
            return CompletableFuture.completedFuture(null);
        }
    }
}
