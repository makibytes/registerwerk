package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BlockchainClientRegistry routing: quarantine, ranking, failover client (P4C-1 / P4B-8)")
class BlockchainClientRegistryRoutingTest {

    private Web3jClientFactory factory;
    private BlockchainClientRegistry registry;
    private ChainConfig chain;
    private final java.util.Map<String, Web3j> clientsByUrl = new HashMap<>();
    private Web3j failoverClient;

    @BeforeEach
    void setUp() {
        factory = mock(Web3jClientFactory.class);
        failoverClient = mock(Web3j.class);
        when(factory.createClient(anyString())).thenAnswer(i -> clientsByUrl.computeIfAbsent(i.getArgument(0), u -> mock(Web3j.class)));
        when(factory.createService(anyString())).thenAnswer(i -> mock(Web3jService.class));
        when(factory.createClient(any(Web3jService.class))).thenReturn(failoverClient);
        registry = new BlockchainClientRegistry(new HashMap<>(), new HashMap<>(), new HashMap<>(), Optional.empty(),
                Optional.of(factory), Optional.empty(), Optional.empty());
        chain = new ChainConfig();
        chain.setId(UUID.randomUUID());
        chain.setIdentifier("ETHEREUM_MAINNET");
        chain.setChainType(ChainConfig.ChainType.EVM);
        chain.setEnabled(true);
    }

    private RpcNode node(String url, boolean healthy, String reason, int lag) {
        RpcNode n = new RpcNode();
        ReflectionTestUtils.setField(n, "id", UUID.randomUUID());
        n.setChainConfig(chain);
        n.setUrl(url);
        n.setEnabled(true);
        n.setHealthy(healthy);
        n.setHealthReason(reason);
        n.setLagFromBest(lag);
        n.setLastSuccessAt(Instant.now());
        return n;
    }

    @Test
    @DisplayName("a chain-mismatching node is never routed to, even when it is the only node")
    void mismatchNeverRouted() {
        registry.refreshFromNodes(List.of(node("https://foreign", false, RpcNode.HealthReason.CHAIN_MISMATCH, 0)));

        assertThatThrownBy(() -> registry.getEvmClientByIdentifier("ETHEREUM_MAINNET"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("quarantined");
    }

    @Test
    @DisplayName("a mismatching node with a better lag does not beat an honest node, nor is it the fallback")
    void mismatchSkippedForHonestNode() {
        registry.refreshFromNodes(List.of(
                node("https://foreign", true, RpcNode.HealthReason.CHAIN_MISMATCH, 0),
                node("https://honest", false, RpcNode.HealthReason.LAGGING, 5)));

        // single routable node -> its own client, not the failover client
        assertThat(registry.getEvmClientByIdentifier("ETHEREUM_MAINNET")).isSameAs(clientsByUrl.get("https://honest"));
        assertThat(registry.evmNodeClients("ETHEREUM_MAINNET")).hasSize(1);
    }

    @Test
    @DisplayName("two routable nodes are served through the failover client")
    void failoverClientForTwoNodes() {
        registry.refreshFromNodes(List.of(node("https://a", true, null, 0), node("https://b", true, null, 1)));

        assertThat(registry.getEvmClientByIdentifier("ETHEREUM_MAINNET")).isSameAs(failoverClient);
    }

    @Test
    @DisplayName("a node that failed a call is ranked behind healthy nodes before the next health round")
    void suspectNodeRankedLast() {
        RpcNode a = node("https://a", true, null, 0);
        RpcNode b = node("https://b", true, null, 1);
        registry.refreshFromNodes(List.of(a, b));
        assertThat(registry.evmNodeClients("ETHEREUM_MAINNET").getFirst().nodeId()).isEqualTo(a.getId());

        registry.reportTransportFailure(a.getId());

        assertThat(registry.evmNodeClients("ETHEREUM_MAINNET").getFirst().nodeId()).isEqualTo(b.getId());
    }

    @Test
    @DisplayName("evmNodeClients exposes direct per-node clients for second-source checks, best first")
    void evmNodeClientsAreDirect() {
        registry.refreshFromNodes(List.of(node("https://a", true, null, 0), node("https://b", true, null, 0)));

        List<BlockchainClientRegistry.EvmNodeClient> nodes = registry.evmNodeClients("ETHEREUM_MAINNET");
        assertThat(nodes).extracting(BlockchainClientRegistry.EvmNodeClient::client)
                .containsExactlyInAnyOrder(clientsByUrl.get("https://a"), clientsByUrl.get("https://b"));
    }
}
