package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.SolanaClientFactory;
import de.makibytes.registerwerk.blockchain.api.Web3jClientFactory;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit test for the {@code registerwerk_rpc_nodes_unhealthy_total} gauge only — the full
 * health-check service opens real Web3j/Solana/Canton clients, which is impractical to unit
 * test meaningfully; the gauge itself is a live query over already-persisted RpcNode.healthy
 * state (alerting metrics), so it can be verified independently of
 * checkAllNodes() ever running.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RpcNodeHealthService — unhealthy-node gauge")
class RpcNodeHealthServiceTest {

    @Mock private RpcNodeRepository rpcNodeRepository;
    @Mock private RpcNodeHealthPersister healthPersister;
    @Mock private Web3jClientFactory web3jClientFactory;
    @Mock private SolanaClientFactory solanaClientFactory;
    @Mock private BlockchainClientRegistry registry;

    private SimpleMeterRegistry meterRegistry;
    private RpcNodeHealthService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new RpcNodeHealthService(
                rpcNodeRepository, healthPersister, web3jClientFactory, solanaClientFactory, null, registry,
                meterRegistry);
    }

    @Test
    @DisplayName("gauge reflects a live count of unhealthy RpcNode rows")
    void gauge_reflectsLiveUnhealthyCount() {
        when(rpcNodeRepository.countByHealthyFalse()).thenReturn(2L);

        double value = meterRegistry.get("registerwerk_rpc_nodes_unhealthy_total").gauge().value();

        assertThat(value).isEqualTo(2.0);
    }

    @Test
    @DisplayName("gauge reads 0 when every node is healthy")
    void gauge_zeroWhenAllHealthy() {
        when(rpcNodeRepository.countByHealthyFalse()).thenReturn(0L);

        double value = meterRegistry.get("registerwerk_rpc_nodes_unhealthy_total").gauge().value();

        assertThat(value).isZero();
    }
}
