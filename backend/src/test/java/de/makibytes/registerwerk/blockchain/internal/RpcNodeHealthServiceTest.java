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

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcNode;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock private RpcChainIdentityChecker identityChecker;

    private SimpleMeterRegistry meterRegistry;
    private RpcNodeHealthService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new RpcNodeHealthService(
                rpcNodeRepository, healthPersister, web3jClientFactory, solanaClientFactory, null, registry,
                identityChecker, meterRegistry);
        ReflectionTestUtils.setField(service, "maxLagBlocks", 2);
        ReflectionTestUtils.setField(service, "stallThresholdSeconds", 120L);
        ReflectionTestUtils.setField(service, "maxPlausibleJump", 1000L);
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

    // ── P4B-8 / P4C-1: health rules ───────────────────────────────────────────

    private RpcNode node(String url, boolean healthy) {
        RpcNode n = new RpcNode();
        ReflectionTestUtils.setField(n, "id", UUID.randomUUID());
        ChainConfig c = new ChainConfig();
        c.setId(UUID.nameUUIDFromBytes("chain".getBytes()));
        c.setIdentifier("ETHEREUM_MAINNET");
        n.setChainConfig(c);
        n.setUrl(url);
        n.setEnabled(true);
        n.setHealthy(healthy);
        n.setLastCheckedAt(Instant.now());
        n.setBlockLastAdvancedAt(Instant.now());
        return n;
    }

    private static RpcNodeHealthService.Probes probes(java.util.Map<RpcNode, Long> heights) {
        RpcNodeHealthService.Probes p = new RpcNodeHealthService.Probes();
        heights.forEach((n, h) -> p.reported.put(n.getId(), h));
        return p;
    }

    @Test
    @DisplayName("best block is the median: one node reporting a huge height cannot make honest nodes lag")
    void medianReferenceIgnoresLyingNode() {
        RpcNode a = node("https://a", true), b = node("https://b", true), c = node("https://c", true),
                liar = node("https://liar", true);
        var heights = new java.util.LinkedHashMap<RpcNode, Long>();
        heights.put(a, 1000L); heights.put(b, 1001L); heights.put(c, 1000L); heights.put(liar, 9_000_000L);

        service.applyLagAndHealth(List.of(a, b, c, liar), probes(heights), true);

        assertThat(a.isHealthy()).isTrue();
        assertThat(b.isHealthy()).isTrue();
        assertThat(c.isHealthy()).isTrue();
        assertThat(liar.isHealthy()).isFalse();
        assertThat(liar.getHealthReason()).isEqualTo(RpcNode.HealthReason.IMPLAUSIBLE_HEIGHT);
        assertThat(RpcNodeHealthService.referenceHeight(List.of(a, b, c, liar),
                probes(heights).reported,
                java.util.Set.of(a.getId(), b.getId(), c.getId(), liar.getId()))).isEqualTo(1000L);
    }

    @Test
    @DisplayName("two nodes: a far-ahead node is NOT quarantined by the peer's height alone")
    void twoNodesNoJumpQuarantine() {
        RpcNode a = node("https://a", true), b = node("https://b", true);
        var heights = new java.util.LinkedHashMap<RpcNode, Long>();
        heights.put(a, 500L); heights.put(b, 500_000L);

        service.applyLagAndHealth(List.of(a, b), probes(heights), true);

        assertThat(b.isHealthy()).isTrue();
        assertThat(b.getHealthReason()).isNotEqualTo(RpcNode.HealthReason.IMPLAUSIBLE_HEIGHT);
    }

    @Test
    @DisplayName("two nodes, peer stalled 5000 blocks behind: the honest node stays healthy and routable")
    void twoNodesStalledPeerDoesNotQuarantineHonestNode() {
        RpcNode honest = node("https://honest", true), stalled = node("https://stalled", true);
        stalled.setBlockLastAdvancedAt(Instant.now().minusSeconds(3600));
        var heights = new java.util.LinkedHashMap<RpcNode, Long>();
        heights.put(honest, 105_000L); heights.put(stalled, 100_000L);

        service.applyLagAndHealth(List.of(honest, stalled), probes(heights), true);

        assertThat(honest.isHealthy()).isTrue();
        assertThat(honest.getHealthReason()).isNull();
        assertThat(stalled.isHealthy()).isFalse();
    }

    @Test
    @DisplayName("two nodes, peer syncing far behind: the honest node stays healthy")
    void twoNodesSyncingPeerDoesNotQuarantineHonestNode() {
        RpcNode honest = node("https://honest", true), syncing = node("https://syncing", true);
        syncing.setSyncing(true);
        var heights = new java.util.LinkedHashMap<RpcNode, Long>();
        heights.put(honest, 105_000L); heights.put(syncing, 10L);

        service.applyLagAndHealth(List.of(honest, syncing), probes(heights), true);

        assertThat(honest.isHealthy()).isTrue();
        assertThat(syncing.getHealthReason()).isEqualTo(RpcNode.HealthReason.SYNCING);
    }

    @Test
    @DisplayName("the implausible-height quarantine never removes the last healthy node")
    void implausibleNeverLeavesZeroHealthy() {
        // a, b, c are still recovering (unhealthy, need consecutive good probes), so 'ahead' is the only healthy one
        RpcNode a = node("https://a", false), b = node("https://b", false), c = node("https://c", false),
                ahead = node("https://ahead", true);
        var heights = new java.util.LinkedHashMap<RpcNode, Long>();
        heights.put(a, 100L); heights.put(b, 100L); heights.put(c, 100L); heights.put(ahead, 50_000L);

        service.applyLagAndHealth(List.of(a, b, c, ahead), probes(heights), true);

        assertThat(ahead.isHealthy()).as("last routable node stays in service").isTrue();
    }

    @Test
    @DisplayName("a chain-mismatching node is unhealthy with reason CHAIN_MISMATCH and stays out of the reference")
    void mismatchQuarantined() {
        RpcNode good = node("https://good", true), foreign = node("https://foreign", true);
        RpcNodeHealthService.Probes p = probes(java.util.Map.of(good, 100L));
        p.mismatched.add(foreign.getId());

        service.applyLagAndHealth(List.of(good, foreign), p, true);

        assertThat(foreign.isHealthy()).isFalse();
        assertThat(foreign.getHealthReason()).isEqualTo(RpcNode.HealthReason.CHAIN_MISMATCH);
        assertThat(good.isHealthy()).isTrue();
    }

    @Test
    @DisplayName("the first failed probe makes a healthy node unhealthy at once (no 120 s stall wait)")
    void firstFailureUnhealthy() {
        RpcNode dead = node("https://dead", true), alive = node("https://alive", true);
        RpcNodeHealthService.Probes p = probes(java.util.Map.of(alive, 100L));
        p.failed.add(dead.getId());

        service.applyLagAndHealth(List.of(dead, alive), p, true);

        assertThat(dead.isHealthy()).isFalse();
        assertThat(dead.getHealthReason()).isEqualTo(RpcNode.HealthReason.PROBE_FAILED);
        assertThat(alive.isHealthy()).isTrue();
    }

    @Test
    @DisplayName("an unhealthy node needs two consecutive good probes to be healthy again")
    void recoveryHysteresis() {
        RpcNode n = node("https://n", false);
        n.setHealthReason(RpcNode.HealthReason.PROBE_FAILED);

        service.applyLagAndHealth(List.of(n), probes(java.util.Map.of(n, 100L)), true);
        assertThat(n.isHealthy()).isFalse();
        assertThat(n.getHealthReason()).isEqualTo(RpcNode.HealthReason.RECOVERING);

        service.applyLagAndHealth(List.of(n), probes(java.util.Map.of(n, 101L)), true);
        assertThat(n.isHealthy()).isTrue();
        assertThat(n.getHealthReason()).isNull();
    }
}
