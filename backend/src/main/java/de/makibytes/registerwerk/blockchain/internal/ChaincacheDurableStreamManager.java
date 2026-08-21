package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A second {@link BlockFinalityFeed} producer, alongside {@code indexer.internal.ReorgGuard}'s
 * poll-based RPC probing: for every {@link ChainConfig} that opted into
 * {@link ChainConfig.FinalitySource#CHAINCACHE}, subscribes to that chain's SAFE and FINALIZED
 * durable event streams on its {@link RpcNode.NodeKind#CHAINCACHE} node via
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
class ChaincacheDurableStreamManager {

    private static final Logger log = LoggerFactory.getLogger(ChaincacheDurableStreamManager.class);

    private final ChainConfigRepository chainConfigRepository;
    private final RpcNodeRepository rpcNodeRepository;
    private final BlockFinalityFeed blockFinalityFeed;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final Map<UUID, ChainSubscription> activeByChain = new ConcurrentHashMap<>();

    ChaincacheDurableStreamManager(ChainConfigRepository chainConfigRepository, RpcNodeRepository rpcNodeRepository,
                                   BlockFinalityFeed blockFinalityFeed, ObjectMapper objectMapper) {
        this.chainConfigRepository = chainConfigRepository;
        this.rpcNodeRepository = rpcNodeRepository;
        this.blockFinalityFeed = blockFinalityFeed;
        this.objectMapper = objectMapper;
    }

    /**
     * Opens a connection for every qualifying chain that doesn't already have a live one, and
     * closes any connection whose chain no longer qualifies (disabled, reverted to
     * {@code RPC_SELF_PROBE}, or its chaincache node was disabled/removed) — the reconnect path
     * for a dropped connection is simply "it's no longer in {@link #activeByChain}, so the next
     * tick opens a fresh one", rather than separate retry/backoff bookkeeping.
     */
    @SchedulerLock(name = "chaincacheDurableStreamManagerReconcile", lockAtMostFor = "PT1M", lockAtLeastFor = "PT5S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
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
            RpcNode node = nodes.get(0);
            connect(chainId, node);
        }
    }

    private void connect(UUID chainId, RpcNode node) {
        String wsUrl = toWebSocketUrl(node.getManagementUrl(), node.getRemoteChainKey());
        if (wsUrl == null) {
            log.warn("chaincache node id={} for chain={} has no usable managementUrl/remoteChainKey; skipping",
                    node.getId(), chainId);
            return;
        }
        String consumerId = "registerwerk-" + chainId;
        ChainSubscription subscription = new ChainSubscription(chainId, consumerId);
        activeByChain.put(chainId, subscription);
        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), subscription)
                .whenComplete((ws, failure) -> {
                    if (failure != null) {
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
     * One WebSocket connection per chain, subscribing to both the SAFE and FINALIZED durable
     * streams (each block appears once per stream it reaches — see chaincache's
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
            String blockHash = event.path("blockHash").asText(null);
            long sequence = event.path("sequence").asLong();

            if ("RETRACTION".equals(kind) && blockNumber != null) {
                blockFinalityFeed.recordRetraction(chainId, blockNumber, blockHash, 0);
            } else if ("BLOCK".equals(kind) && blockNumber != null && blockHash != null) {
                blockFinalityFeed.recordObservation(chainId, blockNumber, blockHash, level);
            }
            acknowledge(webSocket, level, sequence);
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
            activeByChain.remove(chainId, this);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("chaincache durable stream closed for chain={}: {} {}", chainId, statusCode, reason);
            closed = true;
            activeByChain.remove(chainId, this);
            return CompletableFuture.completedFuture(null);
        }
    }
}
