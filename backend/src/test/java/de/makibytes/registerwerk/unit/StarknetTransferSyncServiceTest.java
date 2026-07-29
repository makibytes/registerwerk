package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import de.makibytes.registerwerk.indexer.internal.StarknetTransferSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("StarknetTransferSyncService unit tests")
class StarknetTransferSyncServiceTest {

    private static final String RPC_URL = "http://localhost/starknet-rpc";
    private static final String TRANSFER_SELECTOR =
            "0x99cd8bde557814842a3121e8ddfd433a539b8c9f14bf31ebf108d12e6196e9";

    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private IndexerStateRepository indexerStateRepository;
    @Mock private TokenTransferRepository tokenTransferRepository;
    @Mock private AssetDeploymentRepository assetDeploymentRepository;

    private final ExplorerUrlBuilder explorerUrlBuilder = new ExplorerUrlBuilder();
    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private MockRestServiceServer mockServer;
    private StarknetTransferSyncService service;

    private final UUID assetId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        service = new StarknetTransferSyncService(chainConfigRepository, indexerStateRepository,
                tokenTransferRepository, assetDeploymentRepository, explorerUrlBuilder, restClientBuilder);
    }

    /** Only needed by tests that reach past the "no watched deployments" early-return. */
    private void stubEmptyIndexerState() {
        when(indexerStateRepository.findByChainConfigIdAndIndexerType(chainConfigId, IndexerState.IndexerType.STARKNET_POLL))
                .thenReturn(Optional.empty());
        when(indexerStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ChainConfig starknetChain() {
        ChainConfig chain = new ChainConfig();
        chain.setId(chainConfigId);
        chain.setRpcUrl(RPC_URL);
        chain.setNetworkType(ChainConfig.NetworkType.TESTNET);
        return chain;
    }

    private AssetDeployment deployment(String contractAddress) {
        AssetDeployment d = new AssetDeployment();
        d.setId(deploymentId);
        d.setAssetId(assetId);
        d.setContractAddress(contractAddress);
        return d;
    }

    @Test
    @DisplayName("syncChain decodes a mint (from=0) Transfer event and links it to the deployment")
    void syncChain_decodesMintEventAndLinksDeployment() {
        stubEmptyIndexerState();
        ChainConfig chain = starknetChain();
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STARKNET, Network.TESTNET))
                .thenReturn(List.of(deployment("0x1a2b3c")));
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(any(), any(), any()))
                .thenReturn(false);

        mockServer.expect(requestTo(RPC_URL))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":100}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(RPC_URL))
                .andRespond(withSuccess("""
                    {"jsonrpc":"2.0","id":1,"result":{"events":[
                      {"from_address":"0x1a2b3c","keys":["%s","0x0","0xabc"],
                       "data":["0x64","0x0"],"block_number":10,"transaction_hash":"0xdeadbeef"}
                    ]}}
                    """.formatted(TRANSFER_SELECTOR), MediaType.APPLICATION_JSON));

        service.syncChain(chain);

        ArgumentCaptor<TokenTransfer> captor = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(tokenTransferRepository).save(captor.capture());
        TokenTransfer saved = captor.getValue();

        assertThat(saved.getEventType()).isEqualTo(TokenTransfer.EventType.MINT);
        assertThat(saved.getFromAddress()).isNull();
        assertThat(saved.getToAddress()).isEqualTo("0xabc");
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(saved.getContractAddress()).isEqualTo("0x1a2b3c");
        assertThat(saved.getBlockNumber()).isEqualTo(10L);
        assertThat(saved.getLogIndex()).isEqualTo(0);
        assertThat(saved.getDeploymentId()).isEqualTo(deploymentId);
        assertThat(saved.getAssetId()).isEqualTo(assetId);

        ArgumentCaptor<IndexerState> stateCaptor = ArgumentCaptor.forClass(IndexerState.class);
        verify(indexerStateRepository, org.mockito.Mockito.atLeastOnce()).save(stateCaptor.capture());
        IndexerState finalState = stateCaptor.getValue();
        assertThat(finalState.getLastSyncedBlock()).isEqualTo(100L);
        assertThat(finalState.getStatus()).isEqualTo(IndexerState.IndexerStatus.ACTIVE);

        mockServer.verify();
    }

    @Test
    @DisplayName("syncChain decodes a transfer between two non-zero addresses as TRANSFER")
    void syncChain_decodesTransferBetweenHolders() {
        stubEmptyIndexerState();
        ChainConfig chain = starknetChain();
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STARKNET, Network.TESTNET))
                .thenReturn(List.of(deployment("0x1a2b3c")));
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(any(), any(), any()))
                .thenReturn(false);

        mockServer.expect(requestTo(RPC_URL))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":55}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(RPC_URL))
                .andRespond(withSuccess("""
                    {"jsonrpc":"2.0","id":1,"result":{"events":[
                      {"from_address":"0x1a2b3c","keys":["%s","0x111","0x222"],
                       "data":["0x5","0x0"],"block_number":42,"transaction_hash":"0xfeed"}
                    ]}}
                    """.formatted(TRANSFER_SELECTOR), MediaType.APPLICATION_JSON));

        service.syncChain(chain);

        ArgumentCaptor<TokenTransfer> captor = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(tokenTransferRepository).save(captor.capture());
        TokenTransfer saved = captor.getValue();

        assertThat(saved.getEventType()).isEqualTo(TokenTransfer.EventType.TRANSFER);
        assertThat(saved.getFromAddress()).isEqualTo("0x111");
        assertThat(saved.getToAddress()).isEqualTo("0x222");
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("5"));

        mockServer.verify();
    }

    @Test
    @DisplayName("syncChain skips already-seen transfers (dedup by chain/txHash/logIndex)")
    void syncChain_skipsDuplicateTransfers() {
        stubEmptyIndexerState();
        ChainConfig chain = starknetChain();
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STARKNET, Network.TESTNET))
                .thenReturn(List.of(deployment("0x1a2b3c")));
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(eq(chainConfigId), eq("0xdeadbeef"), eq(0)))
                .thenReturn(true);

        mockServer.expect(requestTo(RPC_URL))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":100}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(RPC_URL))
                .andRespond(withSuccess("""
                    {"jsonrpc":"2.0","id":1,"result":{"events":[
                      {"from_address":"0x1a2b3c","keys":["%s","0x0","0xabc"],
                       "data":["0x64","0x0"],"block_number":10,"transaction_hash":"0xdeadbeef"}
                    ]}}
                    """.formatted(TRANSFER_SELECTOR), MediaType.APPLICATION_JSON));

        service.syncChain(chain);

        verify(tokenTransferRepository, never()).save(any());
        mockServer.verify();
    }

    @Test
    @DisplayName("syncChain no-ops when no Starknet deployment has a known contract address")
    void syncChain_noOpsWithoutWatchedDeployments() {
        ChainConfig chain = starknetChain();
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STARKNET, Network.TESTNET))
                .thenReturn(List.of());

        service.syncChain(chain);

        verify(tokenTransferRepository, never()).save(any());
        verify(indexerStateRepository, never()).save(any());
    }
}
