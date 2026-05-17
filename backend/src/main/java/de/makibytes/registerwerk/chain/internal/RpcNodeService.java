package de.makibytes.registerwerk.chain.internal;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.chain.web.dto.RpcNodeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class RpcNodeService {

    private static final Logger log = LoggerFactory.getLogger(RpcNodeService.class);

    private final RpcNodeRepository rpcNodeRepository;
    private final ChainConfigRepository chainConfigRepository;

    public RpcNodeService(RpcNodeRepository rpcNodeRepository, ChainConfigRepository chainConfigRepository) {
        this.rpcNodeRepository = rpcNodeRepository;
        this.chainConfigRepository = chainConfigRepository;
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
        ChainConfig chain = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));

        RpcNode node = new RpcNode();
        node.setChainConfig(chain);
        node.setUrl(url);
        node.setLabel(label != null ? label : "Node");
        node.setEnabled(true);

        RpcNode saved = rpcNodeRepository.save(node);
        log.info("Added RPC node: id={}, url={}, chain={}", saved.getId(), url, chain.getIdentifier());
        return saved;
    }

    public void enable(UUID nodeId) {
        RpcNode node = getById(nodeId);
        node.setEnabled(true);
        rpcNodeRepository.save(node);
        log.info("Enabled RPC node: id={}, url={}", nodeId, node.getUrl());
    }

    public void disable(UUID nodeId) {
        RpcNode node = getById(nodeId);
        node.setEnabled(false);
        rpcNodeRepository.save(node);
        log.info("Disabled RPC node: id={}, url={}", nodeId, node.getUrl());
    }

    /**
     * Toggles the exclusive flag for a node. When exclusive is set, only nodes
     * with exclusive=true (per chain) are considered for routing.
     */
    public void setExclusive(UUID nodeId, boolean exclusive) {
        RpcNode node = getById(nodeId);
        node.setExclusive(exclusive);
        rpcNodeRepository.save(node);
        log.info("Set RPC node exclusive={}: id={}, url={}", exclusive, nodeId, node.getUrl());
    }

    public void delete(UUID nodeId) {
        RpcNode node = getById(nodeId);
        rpcNodeRepository.delete(node);
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
                node.isSyncing()
        );
    }

    private RpcNode getById(UUID id) {
        return rpcNodeRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("RpcNode", id));
    }
}
