package de.makibytes.registerwerk.chain.internal;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.chain.web.dto.RpcNodeResponse;
import de.makibytes.registerwerk.chain.events.RpcNodeChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class RpcNodeService {

    private static final Logger log = LoggerFactory.getLogger(RpcNodeService.class);

    private final RpcNodeRepository rpcNodeRepository;
    private final ChainConfigRepository chainConfigRepository;
    private final ApplicationEventPublisher events;
    private final ChaincacheClient chaincacheClient;

    public RpcNodeService(RpcNodeRepository rpcNodeRepository, ChainConfigRepository chainConfigRepository,
                          ApplicationEventPublisher events, ChaincacheClient chaincacheClient) {
        this.rpcNodeRepository = rpcNodeRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.events = events;
        this.chaincacheClient = chaincacheClient;
    }

    @Transactional(readOnly = true)
    public List<RpcNode> listByChain(UUID chainConfigId) {
        return rpcNodeRepository.findByChainConfig_IdWithChainConfig(chainConfigId);
    }

    @Transactional(readOnly = true)
    public List<RpcNode> listAll() {
        return rpcNodeRepository.findAllWithChainConfig();
    }

    public RpcNode addNode(UUID chainConfigId, String url, String label) {
        return addNode(chainConfigId, url, label, RpcNode.NodeKind.DIRECT_RPC, null, null);
    }

    /**
     * Adds a node, distinguishing a {@link RpcNode.NodeKind#CHAINCACHE} connection (its own
     * capability guarantees, probed here and periodically thereafter) from a
     * {@link RpcNode.NodeKind#DIRECT_RPC} one — see {@link RpcNode.NodeKind}'s javadoc for why
     * this distinction is deliberately visible rather than hidden behind an identical
     * URL-in-a-table. A capability-probe failure on add is logged, not rejected: the operator is
     * often adding a node before chaincache itself is reachable (e.g. wiring up the demo compose
     * profile before `docker compose up` has finished), and {@code capabilities} simply stays null
     * until the next successful probe.
     */
    public RpcNode addNode(UUID chainConfigId, String url, String label,
                           RpcNode.NodeKind kind, String managementUrl, String remoteChainKey) {
        ChainConfig chain = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));

        RpcNode node = new RpcNode();
        node.setChainConfig(chain);
        node.setUrl(url);
        node.setLabel(label != null ? label : "Node");
        node.setEnabled(true);
        node.setKind(kind != null ? kind : RpcNode.NodeKind.DIRECT_RPC);
        node.setManagementUrl(managementUrl);
        node.setRemoteChainKey(remoteChainKey);

        if (node.getKind() == RpcNode.NodeKind.CHAINCACHE) {
            probeAndApplyCapabilities(node);
        }

        RpcNode saved = rpcNodeRepository.save(node);
        events.publishEvent(new RpcNodeChangedEvent(saved.getId(), null, null, "ADDED", chainConfigId));
        log.info("Added RPC node: id={}, url={}, chain={}, kind={}", saved.getId(), url, chain.getIdentifier(), node.getKind());
        return saved;
    }

    /**
     * Updates a node's connection details — previously missing entirely (only add/delete
     * existed), which made the {@code chaincache-experiment} compose profile's stale env vars
     * (fixed URL, wrong route prefix) impossible to correct without deleting and re-adding the
     * node and losing its health history.
     */
    public RpcNode updateNode(UUID chainId, UUID nodeId, String url, String label,
                              RpcNode.NodeKind kind, String managementUrl, String remoteChainKey) {
        RpcNode node = getById(chainId, nodeId);
        node.setUrl(url);
        node.setLabel(label != null ? label : node.getLabel());
        if (kind != null) {
            node.setKind(kind);
        }
        node.setManagementUrl(managementUrl);
        node.setRemoteChainKey(remoteChainKey);

        if (node.getKind() == RpcNode.NodeKind.CHAINCACHE) {
            probeAndApplyCapabilities(node);
        } else {
            node.setCapabilities(null);
        }

        RpcNode saved = rpcNodeRepository.save(node);
        events.publishEvent(new RpcNodeChangedEvent(nodeId, null, null, "UPDATED", chainId));
        log.info("Updated RPC node: id={}, url={}", nodeId, url);
        return saved;
    }

    /** Re-probes an existing {@link RpcNode.NodeKind#CHAINCACHE} node's capabilities on demand
     *  (operator-triggered refresh, distinct from the periodic background probe a future
     *  scheduler may add) — a no-op returning the node unchanged for a {@code DIRECT_RPC} node. */
    public RpcNode refreshCapabilities(UUID chainId, UUID nodeId) {
        RpcNode node = getById(chainId, nodeId);
        if (node.getKind() == RpcNode.NodeKind.CHAINCACHE) {
            probeAndApplyCapabilities(node);
            rpcNodeRepository.save(node);
        }
        return node;
    }

    private void probeAndApplyCapabilities(RpcNode node) {
        Optional<ChaincacheClient.ChainCapabilitiesProbe> probe =
                chaincacheClient.probeCapabilities(node.getManagementUrl(), node.getRemoteChainKey());
        if (probe.isPresent()) {
            node.setCapabilities(toMap(probe.get()));
        } else {
            log.warn("chaincache capability probe failed or found no matching chain={} at {}",
                    node.getRemoteChainKey(), node.getManagementUrl());
        }
    }

    private static java.util.Map<String, Object> toMap(ChaincacheClient.ChainCapabilitiesProbe probe) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("finalityModel", probe.finalityModel());
        map.put("safeConfirmations", probe.safeConfirmations());
        map.put("finalizedConfirmations", probe.finalizedConfirmations());
        map.put("configuredApis", probe.configuredApis());
        map.put("debugApiConfiguredOnAnyNode", probe.debugApiConfiguredOnAnyNode());
        map.put("addressTraceCapability", probe.addressTraceCapability());
        map.put("durableStreamAvailable", probe.durableStreamAvailable());
        map.put("kafkaRelayEnabled", probe.kafkaRelayEnabled());
        map.put("probedAt", java.time.Instant.now().toString());
        return map;
    }

    public void enable(UUID chainId, UUID nodeId) {
        RpcNode node = getById(chainId, nodeId);
        node.setEnabled(true);
        rpcNodeRepository.save(node);
        events.publishEvent(new RpcNodeChangedEvent(nodeId, null, null, "ENABLED", chainId));
        log.info("Enabled RPC node: id={}, url={}", nodeId, node.getUrl());
    }

    public void disable(UUID chainId, UUID nodeId) {
        RpcNode node = getById(chainId, nodeId);
        node.setEnabled(false);
        rpcNodeRepository.save(node);
        events.publishEvent(new RpcNodeChangedEvent(nodeId, null, null, "DISABLED", chainId));
        log.info("Disabled RPC node: id={}, url={}", nodeId, node.getUrl());
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
        rpcNodeRepository.delete(node);
        events.publishEvent(new RpcNodeChangedEvent(nodeId, null, null, "DELETED", chainId));
        log.info("Deleted RPC node: id={}, url={}", nodeId, node.getUrl());
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
                node.getCapabilities()
        );
    }

    private RpcNode getById(UUID chainId, UUID id) {
        return rpcNodeRepository.findByIdAndChainConfig_Id(id, chainId)
                .orElseThrow(() -> new EntityNotFoundException("RpcNode", id));
    }
}
