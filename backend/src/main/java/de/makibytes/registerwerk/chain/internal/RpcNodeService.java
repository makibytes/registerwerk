package de.makibytes.registerwerk.chain.internal;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChaincacheStreamStatus;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.chain.web.dto.RpcNodeResponse;
import de.makibytes.registerwerk.chain.events.RpcNodeChangedEvent;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns {@link RpcNode} lifecycle, including whether a node is a plain {@link RpcNode.NodeKind#DIRECT_RPC}
 * endpoint or a genuine {@link RpcNode.NodeKind#CHAINCACHE} connection — entirely auto-detected
 * from the URL an operator supplies (see {@link ChaincacheClient#detect}), never a field the
 * operator sets. An operator picking the wrong kind for a URL isn't a mistake this product should
 * be able to make in the first place: the same is true of {@link ChainConfig#getFinalitySource()},
 * recomputed here from the chain's actual node set on every change (see
 * {@link #recomputeFinalitySource}) rather than a separate manual setting that could drift from
 * what the nodes themselves say.
 */
@Service
@Transactional
public class RpcNodeService {

    private static final Logger log = LoggerFactory.getLogger(RpcNodeService.class);

    private final RpcNodeRepository rpcNodeRepository;
    private final ChainConfigRepository chainConfigRepository;
    private final ApplicationEventPublisher events;
    private final ChaincacheClient chaincacheClient;
    private final ObjectMapper objectMapper;
    private final ChaincacheStreamStatus streamStatus;
    private final ChaincacheCredentials chaincacheCredentials;

    public RpcNodeService(RpcNodeRepository rpcNodeRepository, ChainConfigRepository chainConfigRepository,
                          ApplicationEventPublisher events, ChaincacheClient chaincacheClient,
                          ObjectMapper objectMapper, ChaincacheStreamStatus streamStatus,
                          ChaincacheCredentials chaincacheCredentials) {
        this.rpcNodeRepository = rpcNodeRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.events = events;
        this.chaincacheClient = chaincacheClient;
        this.objectMapper = objectMapper;
        this.streamStatus = streamStatus;
        this.chaincacheCredentials = chaincacheCredentials;
    }

    @Transactional(readOnly = true)
    public List<RpcNode> listByChain(UUID chainConfigId) {
        return rpcNodeRepository.findByChainConfig_IdWithChainConfig(chainConfigId);
    }

    @Transactional(readOnly = true)
    public List<RpcNode> listAll() {
        return rpcNodeRepository.findAllWithChainConfig();
    }

    /**
     * Adds a node. {@code url} alone determines whether this becomes a
     * {@link RpcNode.NodeKind#CHAINCACHE} connection (see {@link ChaincacheClient#detect}) — there
     * is deliberately no way for the caller to declare a kind. A detection miss (chaincache not
     * reachable yet, or a URL that just isn't one) is not an error: the node is added as
     * {@code DIRECT_RPC} and the periodic redetection job (see {@link #redetectAll}) will pick it
     * up the moment it becomes reachable, exactly as it would for any other add-before-ready race.
     */
    public RpcNode addNode(UUID chainConfigId, String url, String label) {
        ChainConfig chain = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));

        RpcNode node = new RpcNode();
        node.setChainConfig(chain);
        node.setUrl(url);
        node.setLabel(label != null && !label.isBlank() ? label : "Node");
        node.setEnabled(true);
        applyDetection(node, chaincacheClient.detect(url));

        RpcNode saved = rpcNodeRepository.save(node);
        events.publishEvent(new RpcNodeChangedEvent(saved.getId(), null, null, "ADDED", chainConfigId));
        log.info("Added RPC node: id={}, url={}, chain={}, kind={}", saved.getId(), url, chain.getIdentifier(), node.getKind());
        recomputeFinalitySource(chain);
        return saved;
    }

    /**
     * Updates a node's URL/label — previously missing entirely (only add/delete existed), which
     * made a misconfigured URL impossible to correct without deleting and re-adding the node and
     * losing its health history. Re-detects on every update since the URL may have changed.
     */
    public RpcNode updateNode(UUID chainId, UUID nodeId, String url, String label) {
        RpcNode node = getById(chainId, nodeId);
        node.setUrl(url);
        node.setLabel(label != null && !label.isBlank() ? label : node.getLabel());
        applyDetection(node, chaincacheClient.detect(url));

        RpcNode saved = rpcNodeRepository.save(node);
        events.publishEvent(new RpcNodeChangedEvent(nodeId, null, null, "UPDATED", chainId));
        log.info("Updated RPC node: id={}, url={}", nodeId, url);
        recomputeFinalitySource(node.getChainConfig());
        return saved;
    }

    /**
     * Re-runs detection for one node on demand — works in both directions (a {@code DIRECT_RPC}
     * node whose URL turns out to answer as chaincache now gets promoted; a {@code CHAINCACHE}
     * node chaincache stopped serving falls back to {@code DIRECT_RPC}), unlike the old
     * capabilities-only refresh this replaces.
     */
    public RpcNode redetect(UUID chainId, UUID nodeId) {
        RpcNode node = getById(chainId, nodeId);
        applyDetection(node, chaincacheClient.detect(node.getUrl()));
        rpcNodeRepository.save(node);
        recomputeFinalitySource(node.getChainConfig());
        return node;
    }

    /** Consecutive transient (unreachable, not reachable-but-no-match — see
     *  {@link ChaincacheClient.NodeDetection#reachableButNoMatch()}) redetection failures a
     *  confirmed {@code CHAINCACHE} node tolerates before {@link #redetectAll} demotes it to
     *  {@code DIRECT_RPC}. A single network blip must not tear down a working chaincache
     *  connection's durable event stream — see {@code ChaincacheDurableStreamManager}, which
     *  closes the stream the moment {@code finalitySource} flips away from {@code CHAINCACHE}. */
    private static final int DEMOTE_AFTER_CONSECUTIVE_FAILURES = 3;

    /**
     * Periodic background redetection for every enabled node — the reason a freshly-seeded or
     * freshly-added chaincache connection stops needing a manual "redetect" click at all once
     * chaincache itself is up: this job finds it within one tick. Uses the same targeted
     * {@code updateChaincacheDetection} column-only write {@link #applyDetectionTargeted} does,
     * never a full-entity {@code save()}, so it can never race and clobber
     * {@code RpcNodeHealthService}'s concurrent health-field writes on the same row (see
     * {@code RpcNodeRepository.updateHealthFields}'s javadoc) — the two jobs own disjoint columns
     * by construction.
     *
     * <p>Promotion (a {@code DIRECT_RPC} node now answering as chaincache) and a
     * reachable-but-no-longer-matching demotion both apply immediately — hysteresis only applies
     * to demoting an already-confirmed {@code CHAINCACHE} node after an unreachable/erroring
     * probe, and only after {@link #DEMOTE_AFTER_CONSECUTIVE_FAILURES} consecutive ones (see
     * {@link #DEMOTE_AFTER_CONSECUTIVE_FAILURES}'s javadoc for why). A plain {@code DIRECT_RPC}
     * node whose detection is still {@code DIRECT_RPC} needs no write at all.
     */
    @SchedulerLock(name = "rpcNodeChaincacheRedetect", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    @Scheduled(fixedDelayString = "${registerwerk.rpc.chaincache-redetect-interval-ms:60000}",
               initialDelayString = "${registerwerk.rpc.chaincache-redetect-initial-delay-ms:15000}")
    public void redetectAll() {
        List<RpcNode> allNodes = rpcNodeRepository.findAllWithChainConfig();
        List<RpcNode> nodes = allNodes.stream()
                .filter(RpcNode::isEnabled)
                .toList();
        for (RpcNode node : nodes) {
            try {
                redetectOne(node);
            } catch (Exception e) {
                log.warn("Redetection failed for RpcNode id={}: {}", node.getId(), e.getMessage());
            }
        }
        // A chain's finalitySource depends on the (possibly just-changed) kind of its nodes —
        // recompute once per chain after the whole batch rather than once per node above.
        // Include chains whose last Chaincache node was just disabled. Restricting this pass to
        // enabled nodes left those chains permanently stuck on CHAINCACHE finality even though
        // no enabled Chaincache source remained (notably after CHAINCACHE_ENABLED was toggled).
        allNodes.stream().map(RpcNode::getChainConfig).distinct()
                .forEach(this::recomputeFinalitySource);
    }

    private void redetectOne(RpcNode node) {
        ChaincacheClient.NodeDetection detection = chaincacheClient.detect(node.getUrl());

        if (detection.unauthorized()) {
            // ChaincacheClient already logged the specific WARN telling the operator to fix the
            // credential. A bad/missing registerwerk.chaincache.jwt-secret is a config problem,
            // not a signal about what kind this node is — never persist anything here, whatever
            // the node's current kind (leaves a confirmed CHAINCACHE node's durable stream intact).
            return;
        }

        if (detection.kind() == RpcNode.NodeKind.CHAINCACHE) {
            applyDetectionTargeted(node.getId(), detection);
            if (node.getKind() != RpcNode.NodeKind.CHAINCACHE) {
                log.info("RpcNode id={} url={} redetected as CHAINCACHE (was {})",
                        node.getId(), node.getUrl(), node.getKind());
            }
            return;
        }

        if (node.getKind() != RpcNode.NodeKind.CHAINCACHE) {
            // Was never chaincache and still isn't — nothing changed, nothing to persist.
            return;
        }

        if (detection.reachableButNoMatch()) {
            applyDetectionTargeted(node.getId(), detection);
            log.info("RpcNode id={} url={} redetected as DIRECT_RPC (chaincache reachable but no "
                    + "longer lists remoteChainKey={})", node.getId(), node.getUrl(), node.getRemoteChainKey());
            return;
        }

        int failures = node.getChaincacheProbeFailures() + 1;
        if (failures >= DEMOTE_AFTER_CONSECUTIVE_FAILURES) {
            applyDetectionTargeted(node.getId(), detection);
            log.info("RpcNode id={} url={} redetected as DIRECT_RPC after {} consecutive unreachable probes",
                    node.getId(), node.getUrl(), failures);
        } else {
            rpcNodeRepository.incrementChaincacheProbeFailures(node.getId());
            log.debug("RpcNode id={} url={} chaincache probe unreachable ({}/{} before demotion)",
                    node.getId(), node.getUrl(), failures, DEMOTE_AFTER_CONSECUTIVE_FAILURES);
        }
    }

    /**
     * Recomputes {@link ChainConfig#getFinalitySource()} from whether the chain currently has at
     * least one enabled {@code CHAINCACHE}-kind node — the sole source of truth, so a chain's
     * finality source can never drift out of sync with what its nodes actually are. Uses a
     * targeted update rather than a full-entity save for the same clobbering-avoidance reason as
     * {@link #applyDetectionTargeted}.
     */
    private void recomputeFinalitySource(ChainConfig chain) {
        boolean hasEnabledChaincacheNode = rpcNodeRepository
                .existsByChainConfig_IdAndKindAndEnabledTrue(chain.getId(), RpcNode.NodeKind.CHAINCACHE);
        ChainConfig.FinalitySource desired = hasEnabledChaincacheNode
                ? ChainConfig.FinalitySource.CHAINCACHE : ChainConfig.FinalitySource.RPC_SELF_PROBE;
        if (chain.getFinalitySource() != desired) {
            chainConfigRepository.updateFinalitySource(chain.getId(), desired);
            log.info("ChainConfig id={} finalitySource auto-set to {}", chain.getId(), desired);
        }
    }

    public void enable(UUID chainId, UUID nodeId) {
        RpcNode node = getById(chainId, nodeId);
        node.setEnabled(true);
        rpcNodeRepository.save(node);
        events.publishEvent(new RpcNodeChangedEvent(nodeId, null, null, "ENABLED", chainId));
        log.info("Enabled RPC node: id={}, url={}", nodeId, node.getUrl());
        recomputeFinalitySource(node.getChainConfig());
    }

    public void disable(UUID chainId, UUID nodeId) {
        RpcNode node = getById(chainId, nodeId);
        node.setEnabled(false);
        rpcNodeRepository.save(node);
        events.publishEvent(new RpcNodeChangedEvent(nodeId, null, null, "DISABLED", chainId));
        log.info("Disabled RPC node: id={}, url={}", nodeId, node.getUrl());
        recomputeFinalitySource(node.getChainConfig());
    }

    /**
     * Toggles the exclusive flag for a node. When exclusive is set, only nodes
     * with exclusive=true (per chain) are considered for routing.
     */
    public void setExclusive(UUID chainId, UUID nodeId, boolean exclusive) {
        RpcNode node = getById(chainId, nodeId);
        node.setExclusive(exclusive);
        rpcNodeRepository.save(node);
        events.publishEvent(new RpcNodeChangedEvent(nodeId, null, null,
                exclusive ? "PINNED" : "UNPINNED", chainId));
        log.info("Set RPC node exclusive={}: id={}, url={}", exclusive, nodeId, node.getUrl());
    }

    public void delete(UUID chainId, UUID nodeId) {
        RpcNode node = getById(chainId, nodeId);
        ChainConfig chain = node.getChainConfig();
        rpcNodeRepository.delete(node);
        events.publishEvent(new RpcNodeChangedEvent(nodeId, null, null, "DELETED", chainId));
        log.info("Deleted RPC node: id={}, url={}", nodeId, node.getUrl());
        recomputeFinalitySource(chain);
    }

    public RpcNodeResponse toResponse(RpcNode node) {
        return new RpcNodeResponse(
                node.getId(),
                node.getChainConfig().getId(),
                node.getChainConfig().getIdentifier(),
                node.getUrl(),
                node.getLabel(),
                node.isEnabled(),
                node.isExclusive(),
                node.getLatestBlockNumber(),
                node.getBlockLastAdvancedAt(),
                node.getLastCheckedAt(),
                node.getLastSuccessAt(),
                node.isHealthy(),
                node.getConsecutiveFailures(),
                node.getLagFromBest(),
                node.isSyncing(),
                node.getKind(),
                node.getManagementUrl(),
                node.getRemoteChainKey(),
                node.getCapabilities(),
                node.getKind() == RpcNode.NodeKind.CHAINCACHE
                        && streamStatus.isConnected(node.getChainConfig().getId())
        );
    }

    /** Mints a short-lived chaincache bearer token for pasting into chaincache's own operations
     *  console — the same {@code ChaincacheCredentials} port and OPERATOR-role token shape used
     *  for the backend's own server-to-server calls (see {@code ChaincacheClient}), just handed to
     *  the operator instead of attached to an outgoing request. Empty when the node isn't a
     *  chaincache connection, or when {@code registerwerk.chaincache.jwt-secret} isn't configured
     *  on this deployment. */
    @Transactional(readOnly = true)
    public Optional<String> mintConsoleToken(UUID chainId, UUID nodeId) {
        RpcNode node = getById(chainId, nodeId);
        if (node.getKind() != RpcNode.NodeKind.CHAINCACHE || node.getManagementUrl() == null) {
            return Optional.empty();
        }
        return chaincacheCredentials.bearerFor(node.getManagementUrl());
    }

    private RpcNode getById(UUID chainId, UUID id) {
        return rpcNodeRepository.findByIdAndChainConfig_Id(id, chainId)
                .orElseThrow(() -> new EntityNotFoundException("RpcNode", id));
    }

    /** Applies a detection result to an in-memory (not-yet-persisted, or about to be
     *  {@code save()}d wholesale) entity — used by add/update/redetect, which already own the
     *  whole entity for this call. */
    private void applyDetection(RpcNode node, ChaincacheClient.NodeDetection detection) {
        if (detection.unauthorized()) {
            // A bad/missing chaincache credential must not silently rewrite kind/managementUrl/
            // remoteChainKey — ChaincacheClient already logged a specific WARN telling the
            // operator to fix registerwerk.chaincache.jwt-secret. Leave whatever this entity
            // already had: DIRECT_RPC entity defaults for a brand-new node (add), or an existing
            // node's prior confirmed state (update/manual redetect) untouched.
            return;
        }
        node.setKind(detection.kind());
        node.setManagementUrl(detection.managementUrl());
        node.setRemoteChainKey(detection.remoteChainKey());
        node.setCapabilities(detection.capabilities() != null ? toMap(detection.capabilities()) : null);
        // Every caller of this method (add/update/manual redetect) is a fresh, definitive
        // detection — reset the transient-failure counter redetectAll's hysteresis owns.
        node.setChaincacheProbeFailures(0);
    }

    /** The column-only counterpart to {@link #applyDetection}, for the periodic job — never loads
     *  the full entity, so it can never carry a stale copy of health fields back into the row. */
    private void applyDetectionTargeted(UUID nodeId, ChaincacheClient.NodeDetection detection) {
        Map<String, Object> capabilities = detection.capabilities() != null ? toMap(detection.capabilities()) : null;
        String capabilitiesJson = capabilities != null ? objectMapper.writeValueAsString(capabilities) : null;
        rpcNodeRepository.updateChaincacheDetection(nodeId, detection.kind().name(),
                detection.managementUrl(), detection.remoteChainKey(), capabilitiesJson);
    }

    private static Map<String, Object> toMap(ChaincacheClient.ChainCapabilitiesProbe probe) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("durabilityDomainId", probe.durabilityDomainId());
        map.put("finalityModel", probe.finalityModel());
        map.put("safeConfirmations", probe.safeConfirmations());
        map.put("finalizedConfirmations", probe.finalizedConfirmations());
        map.put("configuredApis", probe.configuredApis());
        map.put("debugApiConfiguredOnAnyNode", probe.debugApiConfiguredOnAnyNode());
        if (probe.addressTraceCapability() != null) {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("attempted", probe.addressTraceCapability().attempted());
            trace.put("lastSuccessful", probe.addressTraceCapability().lastSuccessful());
            trace.put("lastAttemptAt", probe.addressTraceCapability().lastAttemptAt());
            map.put("addressTraceCapability", trace);
        } else {
            map.put("addressTraceCapability", null);
        }
        map.put("durableStreamAvailable", probe.durableStreamAvailable());
        map.put("durableProtocolVersion", probe.durableProtocolVersion());
        map.put("configuredNodeCount", probe.configuredNodeCount());
        map.put("availableNodeCount", probe.availableNodeCount());
        map.put("probedAt", Instant.now().toString());
        return map;
    }
}
