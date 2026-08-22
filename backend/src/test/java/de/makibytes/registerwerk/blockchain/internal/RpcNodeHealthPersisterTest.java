package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@code RpcNodeHealthService.checkAll()} used to call {@code RpcNodeRepository.updateHealthFields}
 * (a {@code @Modifying(flushAutomatically = true)} query) directly from its own, non-transactional
 * body — which threw {@code TransactionRequiredException} on every tick in production, since
 * {@code checkAll()} deliberately has no transaction of its own (see its javadoc). This pins that
 * the persist step is now delegated one-per-node to this separate {@code @Transactional} bean.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RpcNodeHealthPersister")
class RpcNodeHealthPersisterTest {

    @Mock private RpcNodeRepository rpcNodeRepository;

    @Test
    @DisplayName("persists the health-check-owned columns of every node in the batch")
    void persist_writesEveryNode() {
        RpcNodeHealthPersister persister = new RpcNodeHealthPersister(rpcNodeRepository);

        persister.persist(List.of(healthyNode(), healthyNode()));

        verify(rpcNodeRepository, times(2)).updateHealthFields(any(), any(), any(), any(), any(),
                anyBoolean(), anyInt(), any(), anyBoolean());
        verifyNoMoreInteractions(rpcNodeRepository);
    }

    @Test
    @DisplayName("an empty batch persists nothing")
    void persist_emptyBatch_noWrites() {
        RpcNodeHealthPersister persister = new RpcNodeHealthPersister(rpcNodeRepository);

        persister.persist(List.of());

        verifyNoMoreInteractions(rpcNodeRepository);
    }

    private RpcNode healthyNode() {
        RpcNode node = new RpcNode();
        node.setLatestBlockNumber(100L);
        node.setBlockLastAdvancedAt(Instant.now());
        node.setLastCheckedAt(Instant.now());
        node.setLastSuccessAt(Instant.now());
        node.setHealthy(true);
        node.setConsecutiveFailures(0);
        node.setLagFromBest(0);
        node.setSyncing(false);
        return node;
    }
}
