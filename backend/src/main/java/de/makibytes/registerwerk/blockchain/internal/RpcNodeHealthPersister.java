package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Persists the health-check-owned {@link RpcNode} columns for a batch of probed nodes.
 *
 * <p>Split out of {@link RpcNodeHealthService} deliberately: {@link RpcNodeRepository#updateHealthFields}
 * is {@code @Modifying(flushAutomatically = true)}, which needs a live transaction to flush against,
 * and {@code RpcNodeHealthService.checkAll()} itself must stay non-transactional (it holds no
 * business reason to keep one transaction open across dozens of sequential outbound network
 * probes with multi-second timeouts each). A plain {@code @Transactional} method on this class is
 * a separate Spring bean, so Spring's transactional proxy actually intercepts the call —
 * annotating a method that {@code checkAll()} calls on itself would not work, since a same-class
 * self-invocation bypasses the proxy entirely.
 */
@Component
class RpcNodeHealthPersister {

    private final RpcNodeRepository rpcNodeRepository;

    RpcNodeHealthPersister(RpcNodeRepository rpcNodeRepository) {
        this.rpcNodeRepository = rpcNodeRepository;
    }

    @Transactional
    void persist(List<RpcNode> nodes) {
        for (RpcNode node : nodes) {
            rpcNodeRepository.updateHealthFields(node.getId(), node.getLatestBlockNumber(),
                    node.getBlockLastAdvancedAt(), node.getLastCheckedAt(), node.getLastSuccessAt(),
                    node.isHealthy(), node.getConsecutiveFailures(), node.getLagFromBest(), node.isSyncing());
        }
    }
}
