package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.SolanaClientFactory;
import de.makibytes.registerwerk.blockchain.api.Web3jClientFactory;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.protocol.Web3j;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for {@link BlockchainClientRegistry#refreshFromNodes}.
 *
 * <p>This method is called every ~30s by {@code RpcNodeHealthService}. It used to read
 * {@code map.getOrDefault(id, factory.createClient(url))}, which looks like "reuse, else create"
 * but — because Java evaluates arguments eagerly — built a client for every node on every round
 * and threw it away on a cache hit. Each thrown-away web3j client pinned a JVM shutdown hook and
 * so could never be collected, which is what eventually pegged every core on GC.
 *
 * <p>The same bug also meant an edited node URL never took effect, because the stale cached
 * client always won the {@code getOrDefault}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlockchainClientRegistry.refreshFromNodes")
class BlockchainClientRegistryRefreshTest {

    @Mock
    private Web3jClientFactory web3jClientFactory;

    @Mock
    private SolanaClientFactory solanaClientFactory;

    private BlockchainClientRegistry registry;
    private ChainConfig ethereum;

    @BeforeEach
    void setUp() {
        registry = new BlockchainClientRegistry(
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                Optional.empty(),
                Optional.of(web3jClientFactory),
                Optional.of(solanaClientFactory),
                Optional.empty());

        ethereum = new ChainConfig();
        ethereum.setId(UUID.randomUUID());
        ethereum.setIdentifier("ethereum-mainnet");
        ethereum.setChainType(ChainConfig.ChainType.EVM);
        ethereum.setEnabled(true);
    }

    private RpcNode node(String url) {
        RpcNode node = new RpcNode();
        ReflectionTestUtils.setField(node, "id", UUID.randomUUID());
        node.setChainConfig(ethereum);
        node.setUrl(url);
        node.setEnabled(true);
        node.setHealthy(true);
        return node;
    }

    @Test
    @DisplayName("builds one client per node on the first refresh")
    void buildsOneClientPerNodeInitially() {
        when(web3jClientFactory.createClient(anyString())).thenReturn(mock(Web3j.class));

        registry.refreshFromNodes(List.of(node("https://a.example"), node("https://b.example")));

        verify(web3jClientFactory, times(2)).createClient(anyString());
    }

    @Test
    @DisplayName("builds no further clients when refreshed repeatedly with unchanged nodes")
    void doesNotRebuildClientsOnRepeatedRefresh() {
        when(web3jClientFactory.createClient(anyString())).thenReturn(mock(Web3j.class));
        List<RpcNode> nodes = List.of(node("https://a.example"), node("https://b.example"));

        registry.refreshFromNodes(nodes);
        verify(web3jClientFactory, times(2)).createClient(anyString());

        // The leak: every one of these rounds used to allocate two more clients, each pinning a
        // JVM shutdown hook, and discard them.
        for (int round = 0; round < 20; round++) {
            registry.refreshFromNodes(nodes);
        }

        verify(web3jClientFactory, times(2)).createClient(anyString());
    }

    @Test
    @DisplayName("reuses the same client instance across refreshes")
    void reusesTheSameClientInstance() {
        Web3j client = mock(Web3j.class);
        when(web3jClientFactory.createClient(anyString())).thenReturn(client);
        List<RpcNode> nodes = List.of(node("https://a.example"));

        registry.refreshFromNodes(nodes);
        Web3j first = registry.getEvmClientByIdentifier("ethereum-mainnet");
        registry.refreshFromNodes(nodes);
        Web3j second = registry.getEvmClientByIdentifier("ethereum-mainnet");

        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("rebuilds the client when the node's URL changes")
    void rebuildsClientWhenUrlChanges() {
        Web3j original = mock(Web3j.class);
        Web3j replacement = mock(Web3j.class);
        when(web3jClientFactory.createClient("https://old.example")).thenReturn(original);
        when(web3jClientFactory.createClient("https://new.example")).thenReturn(replacement);

        RpcNode node = node("https://old.example");
        registry.refreshFromNodes(List.of(node));
        assertThat(registry.getEvmClientByIdentifier("ethereum-mainnet")).isSameAs(original);

        // Same node ID, new endpoint — the cached client is stale and must not be reused.
        node.setUrl("https://new.example");
        registry.refreshFromNodes(List.of(node));

        assertThat(registry.getEvmClientByIdentifier("ethereum-mainnet")).isSameAs(replacement);
    }

    @Test
    @DisplayName("does not touch the Solana factory for an EVM-only node set")
    void doesNotBuildSolanaClientsForEvmNodes() {
        when(web3jClientFactory.createClient(anyString())).thenReturn(mock(Web3j.class));

        registry.refreshFromNodes(List.of(node("https://a.example")));

        verify(solanaClientFactory, never()).createClient(anyString());
    }
}
