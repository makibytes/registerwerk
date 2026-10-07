package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.blockchain.internal.NonceCoordinator;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.finality.api.ChainQuarantinePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.Transaction;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H10: what {@link EvmContractService} tells the {@link NonceCoordinator} before lease repair may reuse a nonce -
 * the pending count of <em>every</em> routable node, and whether any node still knows a transaction.
 */
@DisplayName("EvmContractService nonce source (H10)")
class EvmContractServiceNonceSourceTest {

    private static final String CHAIN = "ETH_MAINNET";
    private static final String ADDRESS = "0x" + "aa".repeat(20);
    private static final String HASH = "0x" + "ab".repeat(32);

    private BlockchainClientRegistry registry;
    private EvmContractService service;
    private Web3j primary;

    @BeforeEach
    void setUp() {
        registry = mock(BlockchainClientRegistry.class);
        service = new EvmContractService(registry, mock(ChainConfigRepository.class), null,
                mock(NonceCoordinator.class), mock(ChainQuarantinePort.class));
        primary = mock(Web3j.class, RETURNS_DEEP_STUBS);
    }

    private static BlockchainClientRegistry.EvmNodeClient node(boolean healthy, long pending) throws Exception {
        Web3j web3j = mock(Web3j.class, RETURNS_DEEP_STUBS);
        EthGetTransactionCount count = new EthGetTransactionCount();
        count.setResult("0x" + Long.toHexString(pending));
        when(web3j.ethGetTransactionCount(ADDRESS, DefaultBlockParameterName.PENDING).send()).thenReturn(count);
        return new BlockchainClientRegistry.EvmNodeClient(UUID.randomUUID(), web3j, healthy);
    }

    private static BlockchainClientRegistry.EvmNodeClient failingNode() throws Exception {
        Web3j web3j = mock(Web3j.class, RETURNS_DEEP_STUBS);
        when(web3j.ethGetTransactionCount(ADDRESS, DefaultBlockParameterName.PENDING).send())
                .thenThrow(new IOException("connection refused"));
        when(web3j.ethGetTransactionByHash(HASH).send()).thenThrow(new IOException("connection refused"));
        return new BlockchainClientRegistry.EvmNodeClient(UUID.randomUUID(), web3j, false);
    }

    private static BlockchainClientRegistry.EvmNodeClient nodeKnowing(Transaction known) throws Exception {
        Web3j web3j = mock(Web3j.class, RETURNS_DEEP_STUBS);
        when(web3j.ethGetTransactionByHash(HASH).send().getTransaction()).thenReturn(Optional.ofNullable(known));
        return new BlockchainClientRegistry.EvmNodeClient(UUID.randomUUID(), web3j, true);
    }

    @Test
    @DisplayName("the authoritative pending nonce is the highest count over all nodes, healthy or not")
    void highestPendingOfAllNodes() throws Exception {
        List<BlockchainClientRegistry.EvmNodeClient> nodes = List.of(node(true, 5), node(true, 9), node(false, 7));
        when(registry.evmNodeClients(CHAIN)).thenReturn(nodes);

        assertThat(service.authoritativePendingNonce(CHAIN, ADDRESS)).contains(BigInteger.valueOf(9));
    }

    @Test
    @DisplayName("one node that cannot answer makes the whole reading unavailable")
    void anyFailingNodeMakesTheReadingUnavailable() throws Exception {
        List<BlockchainClientRegistry.EvmNodeClient> nodes = List.of(node(true, 5), failingNode());
        when(registry.evmNodeClients(CHAIN)).thenReturn(nodes);

        assertThat(service.authoritativePendingNonce(CHAIN, ADDRESS)).isEmpty();
    }

    @Test
    @DisplayName("a JSON-RPC error from a node makes the reading unavailable")
    void rpcErrorMakesTheReadingUnavailable() throws Exception {
        Web3j web3j = mock(Web3j.class, RETURNS_DEEP_STUBS);
        EthGetTransactionCount error = new EthGetTransactionCount();
        error.setError(new org.web3j.protocol.core.Response.Error(-32000, "boom"));
        when(web3j.ethGetTransactionCount(ADDRESS, DefaultBlockParameterName.PENDING).send()).thenReturn(error);
        List<BlockchainClientRegistry.EvmNodeClient> nodes = List.of(node(true, 5),
                new BlockchainClientRegistry.EvmNodeClient(UUID.randomUUID(), web3j, true));
        when(registry.evmNodeClients(CHAIN)).thenReturn(nodes);

        assertThat(service.authoritativePendingNonce(CHAIN, ADDRESS)).isEmpty();
    }

    @Test
    @DisplayName("a chain without a node pool has no authoritative reading")
    void noNodePoolMeansNoReading() {
        when(registry.evmNodeClients(CHAIN)).thenReturn(List.of());

        assertThat(service.authoritativePendingNonce(CHAIN, ADDRESS)).isEmpty();
    }

    @Test
    @DisplayName("a transaction is known when any node returns it, pending or mined")
    void knownWhenAnyNodeReturnsIt() throws Exception {
        List<BlockchainClientRegistry.EvmNodeClient> nodes = List.of(nodeKnowing(null), nodeKnowing(new Transaction()));
        when(registry.evmNodeClients(CHAIN)).thenReturn(nodes);

        assertThat(service.anyNodeKnows(CHAIN, HASH)).isTrue();
    }

    @Test
    @DisplayName("a transaction no node knows is unknown (dropped)")
    void unknownWhenNoNodeReturnsIt() throws Exception {
        List<BlockchainClientRegistry.EvmNodeClient> nodes = List.of(nodeKnowing(null), nodeKnowing(null));
        when(registry.evmNodeClients(CHAIN)).thenReturn(nodes);

        assertThat(service.anyNodeKnows(CHAIN, HASH)).isFalse();
    }

    @Test
    @DisplayName("when a node cannot be asked, or there is no pool, the transaction counts as known (safe side)")
    void unreachableNodeOrNoPoolCountsAsKnown() throws Exception {
        List<BlockchainClientRegistry.EvmNodeClient> nodes = List.of(nodeKnowing(null), failingNode());
        when(registry.evmNodeClients(CHAIN)).thenReturn(nodes);
        assertThat(service.anyNodeKnows(CHAIN, HASH)).isTrue();

        when(registry.evmNodeClients(CHAIN)).thenReturn(List.of());
        assertThat(service.anyNodeKnows(CHAIN, HASH)).isTrue();
    }

    @Test
    @DisplayName("the source combines the failover read with the every-node reading")
    void sourceExposesBothReadings() throws Exception {
        EthGetTransactionCount lagging = new EthGetTransactionCount();
        lagging.setResult("0x4");
        when(primary.ethGetTransactionCount(ADDRESS, DefaultBlockParameterName.PENDING).send()).thenReturn(lagging);
        List<BlockchainClientRegistry.EvmNodeClient> nodes = List.of(node(true, 4), node(true, 6));
        when(registry.evmNodeClients(CHAIN)).thenReturn(nodes);

        NonceCoordinator.ChainNonceSource source = service.nonceSource(primary, CHAIN, ADDRESS);

        assertThat(source.fetch()).isEqualTo(BigInteger.valueOf(4));
        assertThat(source.authoritativePendingNonce()).contains(BigInteger.valueOf(6));
    }
}
