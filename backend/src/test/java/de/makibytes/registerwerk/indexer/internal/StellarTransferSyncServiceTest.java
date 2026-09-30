package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.blockchain.api.StellarUtils;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("StellarTransferSyncService unit tests")
class StellarTransferSyncServiceTest {

    private static final String HORIZON_URL = "http://localhost/horizon";
    private static final String ISSUER = "GISSUERAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String HOLDER = "GHOLDERBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private IndexerStateRepository indexerStateRepository;
    @Mock private TokenTransferRepository tokenTransferRepository;
    @Mock private AssetDeploymentRepository assetDeploymentRepository;
    @Mock private IndexerDeploymentCursorRepository cursorRepository;

    private final Map<UUID, IndexerDeploymentCursor> cursors = new HashMap<>();
    private final List<Exception> recordedFailures = new ArrayList<>();

    private final ExplorerUrlBuilder explorerUrlBuilder = new ExplorerUrlBuilder();
    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private MockRestServiceServer mockServer;
    private StellarTransferSyncService service;

    private final UUID assetId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();
    private String assetCode;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).ignoreExpectOrder(true).build();
        IndexerSyncSupport syncSupport = new IndexerSyncSupport(
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),
                indexerStateRepository) {
            @Override
            void recordFailure(UUID chainConfigId, String chainIdentifier, IndexerState.IndexerType type,
                               Exception failure, int maxErrors) {
                recordedFailures.add(failure);
            }
        };
        lenient().when(cursorRepository.findByDeploymentIdAndIndexerType(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(cursors.get(inv.<UUID>getArgument(0))));
        lenient().when(cursorRepository.save(any())).thenAnswer(inv -> {
            IndexerDeploymentCursor c = inv.getArgument(0);
            cursors.put(c.getDeploymentId(), c);
            return c;
        });
        service = new StellarTransferSyncService(chainConfigRepository, indexerStateRepository,
                tokenTransferRepository, assetDeploymentRepository, cursorRepository, syncSupport,
                explorerUrlBuilder, restClientBuilder,
                io.github.resilience4j.bulkhead.BulkheadRegistry.ofDefaults());
        assetCode = StellarUtils.deriveAssetCode(assetId);
    }

    /** Only needed by tests that reach past the "no watched deployments" early-return. */
    private void stubEmptyIndexerState() {
        lenient().when(indexerStateRepository.findByChainConfigIdAndIndexerType(chainConfigId, IndexerState.IndexerType.STELLAR_HORIZON))
                .thenReturn(Optional.empty());
        lenient().when(indexerStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ChainConfig stellarChain() {
        ChainConfig chain = new ChainConfig();
        chain.setId(chainConfigId);
        chain.setRpcUrl(HORIZON_URL);
        chain.setNetworkType(ChainConfig.NetworkType.TESTNET);
        return chain;
    }

    private AssetDeployment deployment() {
        AssetDeployment d = new AssetDeployment();
        d.setId(deploymentId);
        d.setAssetId(assetId);
        d.setContractAddress(ISSUER);
        return d;
    }

    private String paymentsUrl(String cursor) {
        return operationsUrl(ISSUER, cursor);
    }

    private String operationsUrl(String issuer, String cursor) {
        return HORIZON_URL + "/accounts/" + issuer + "/operations?cursor=" + cursor
                + "&order=asc&limit=200&include_failed=false";
    }

    @Test
    @DisplayName("syncChain classifies a payment FROM the issuer as MINT and links the deployment")
    void syncChain_classifiesIssuancePaymentAsMint() {
        stubEmptyIndexerState();
        ChainConfig chain = stellarChain();
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STELLAR, Network.TESTNET))
                .thenReturn(List.of(deployment()));
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndexAndContractAddress(any(), any(), any(), any()))
                .thenReturn(false);

        mockServer.expect(requestTo(paymentsUrl("0")))
                .andRespond(withSuccess("""
                    {"_embedded":{"records":[
                      {"id":"42949672960","paging_token":"42949672960","type":"payment",
                       "asset_type":"credit_alphanum12","asset_code":"%s","asset_issuer":"%s",
                       "from":"%s","to":"%s","amount":"100.0000000","transaction_hash":"txhash1"}
                    ]}}
                    """.formatted(assetCode, ISSUER, ISSUER, HOLDER), MediaType.APPLICATION_JSON));

        service.syncChain(chain);

        ArgumentCaptor<TokenTransfer> captor = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(tokenTransferRepository).save(captor.capture());
        TokenTransfer saved = captor.getValue();

        assertThat(saved.getEventType()).isEqualTo(TokenTransfer.EventType.MINT);
        assertThat(saved.getFromAddress()).isNull();
        assertThat(saved.getToAddress()).isEqualTo(HOLDER);
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("100.0000000"));
        assertThat(saved.getContractAddress()).isEqualTo(ISSUER);
        assertThat(saved.getBlockNumber()).isEqualTo(10L);
        assertThat(saved.getLogIndex()).isEqualTo(0);
        assertThat(saved.getDeploymentId()).isEqualTo(deploymentId);
        assertThat(saved.getAssetId()).isEqualTo(assetId);

        mockServer.verify();
    }

    @Test
    @DisplayName("occurred_at uses Horizon's real created_at, not processing time")
    void syncChain_usesHorizonCreatedAt_notProcessingTime() {
        stubEmptyIndexerState();
        ChainConfig chain = stellarChain();
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STELLAR, Network.TESTNET))
                .thenReturn(List.of(deployment()));
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndexAndContractAddress(any(), any(), any(), any()))
                .thenReturn(false);

        mockServer.expect(requestTo(paymentsUrl("0")))
                .andRespond(withSuccess("""
                    {"_embedded":{"records":[
                      {"id":"42949672960","paging_token":"42949672960","type":"payment",
                       "asset_type":"credit_alphanum12","asset_code":"%s","asset_issuer":"%s",
                       "from":"%s","to":"%s","amount":"100.0000000","transaction_hash":"txhash1",
                       "created_at":"2026-01-15T10:30:00Z"}
                    ]}}
                    """.formatted(assetCode, ISSUER, ISSUER, HOLDER), MediaType.APPLICATION_JSON));

        service.syncChain(chain);

        ArgumentCaptor<TokenTransfer> captor = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(tokenTransferRepository).save(captor.capture());
        assertThat(captor.getValue().getOccurredAt()).isEqualTo(java.time.Instant.parse("2026-01-15T10:30:00Z"));

        mockServer.verify();
    }

    @Test
    @DisplayName("syncChain classifies a payment TO the issuer as BURN")
    void syncChain_classifiesRedemptionPaymentAsBurn() {
        stubEmptyIndexerState();
        ChainConfig chain = stellarChain();
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STELLAR, Network.TESTNET))
                .thenReturn(List.of(deployment()));
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndexAndContractAddress(any(), any(), any(), any()))
                .thenReturn(false);

        mockServer.expect(requestTo(paymentsUrl("0")))
                .andRespond(withSuccess("""
                    {"_embedded":{"records":[
                      {"id":"42949672961","paging_token":"42949672961","type":"payment",
                       "asset_type":"credit_alphanum12","asset_code":"%s","asset_issuer":"%s",
                       "from":"%s","to":"%s","amount":"25.0000000","transaction_hash":"txhash2"}
                    ]}}
                    """.formatted(assetCode, ISSUER, HOLDER, ISSUER), MediaType.APPLICATION_JSON));

        service.syncChain(chain);

        ArgumentCaptor<TokenTransfer> captor = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(tokenTransferRepository).save(captor.capture());
        TokenTransfer saved = captor.getValue();

        assertThat(saved.getEventType()).isEqualTo(TokenTransfer.EventType.BURN);
        assertThat(saved.getFromAddress()).isEqualTo(HOLDER);
        assertThat(saved.getToAddress()).isNull();

        mockServer.verify();
    }

    @Test
    @DisplayName("syncChain ignores payments for a different asset code/issuer")
    void syncChain_ignoresUnrelatedAssets() {
        stubEmptyIndexerState();
        ChainConfig chain = stellarChain();
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STELLAR, Network.TESTNET))
                .thenReturn(List.of(deployment()));

        mockServer.expect(requestTo(paymentsUrl("0")))
                .andRespond(withSuccess("""
                    {"_embedded":{"records":[
                      {"id":"1","paging_token":"1","type":"payment",
                       "asset_type":"credit_alphanum4","asset_code":"USDC","asset_issuer":"GSOMEOTHERISSUER",
                       "from":"%s","to":"%s","amount":"5.0000000","transaction_hash":"txhash3"}
                    ]}}
                    """.formatted(ISSUER, HOLDER), MediaType.APPLICATION_JSON));

        service.syncChain(chain);

        verify(tokenTransferRepository, never()).save(any());
        mockServer.verify();
    }

    @Test
    @DisplayName("syncChain skips already-seen payments (dedup by chain/txHash/logIndex)")
    void syncChain_skipsDuplicatePayments() {
        stubEmptyIndexerState();
        ChainConfig chain = stellarChain();
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STELLAR, Network.TESTNET))
                .thenReturn(List.of(deployment()));
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndexAndContractAddress(eq(chainConfigId), eq("txhash1"), eq(0), eq(ISSUER)))
                .thenReturn(true);

        mockServer.expect(requestTo(paymentsUrl("0")))
                .andRespond(withSuccess("""
                    {"_embedded":{"records":[
                      {"id":"42949672960","paging_token":"42949672960","type":"payment",
                       "asset_type":"credit_alphanum12","asset_code":"%s","asset_issuer":"%s",
                       "from":"%s","to":"%s","amount":"100.0000000","transaction_hash":"txhash1"}
                    ]}}
                    """.formatted(assetCode, ISSUER, ISSUER, HOLDER), MediaType.APPLICATION_JSON));

        service.syncChain(chain);

        verify(tokenTransferRepository, never()).save(any());
        mockServer.verify();
    }

    @Test
    @DisplayName("syncChain no-ops when no Stellar deployment has a known issuer account")
    void syncChain_noOpsWithoutWatchedDeployments() {
        ChainConfig chain = stellarChain();
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STELLAR, Network.TESTNET))
                .thenReturn(List.of());

        service.syncChain(chain);

        verify(tokenTransferRepository, never()).save(any());
        verify(indexerStateRepository, never()).save(any());
    }

    @Test
    @DisplayName("a clawback of the tracked asset is booked as a BURN of the holder's balance")
    void syncChain_booksClawbackAsBurn() {
        stubEmptyIndexerState();
        ChainConfig chain = stellarChain();
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STELLAR, Network.TESTNET))
                .thenReturn(List.of(deployment()));
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndexAndContractAddress(any(), any(), any(), any()))
                .thenReturn(false);

        mockServer.expect(requestTo(operationsUrl(ISSUER, "0")))
                .andRespond(withSuccess("""
                    {"_embedded":{"records":[
                      {"id":"42949672962","paging_token":"42949672962","type":"clawback",
                       "asset_type":"credit_alphanum12","asset_code":"%s","asset_issuer":"%s",
                       "from":"%s","amount":"12.5000000","source_account":"%s","transaction_hash":"txclaw",
                       "created_at":"2026-02-01T08:00:00Z"},
                      {"id":"42949672963","paging_token":"42949672963","type":"set_trust_line_flags",
                       "asset_type":"credit_alphanum12","asset_code":"%s","asset_issuer":"%s",
                       "trustor":"%s","transaction_hash":"txflags"}
                    ]}}
                    """.formatted(assetCode, ISSUER, HOLDER, ISSUER, assetCode, ISSUER, HOLDER), MediaType.APPLICATION_JSON));

        service.syncChain(chain);

        ArgumentCaptor<TokenTransfer> captor = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(tokenTransferRepository).save(captor.capture());
        TokenTransfer saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(TokenTransfer.EventType.BURN);
        assertThat(saved.getFromAddress()).isEqualTo(HOLDER);
        assertThat(saved.getToAddress()).isNull();
        assertThat(saved.getAmount()).isEqualByComparingTo("12.5");
        assertThat(saved.getDeploymentId()).isEqualTo(deploymentId);
        assertThat(saved.getOccurredAt()).isEqualTo(java.time.Instant.parse("2026-02-01T08:00:00Z"));
        mockServer.verify();
    }

    @Test
    @DisplayName("each deployment has its own cursor: a deployment added later is scanned from 0 while the older one resumes")
    void syncChain_usesPerDeploymentCursor() {
        stubEmptyIndexerState();
        ChainConfig chain = stellarChain();
        String issuer2 = "GISSUER2CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
        UUID deployment2 = UUID.randomUUID();
        AssetDeployment d2 = new AssetDeployment();
        d2.setId(deployment2);
        d2.setAssetId(UUID.randomUUID());
        d2.setContractAddress(issuer2);
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STELLAR, Network.TESTNET))
                .thenReturn(List.of(deployment(), d2));
        IndexerDeploymentCursor existing = new IndexerDeploymentCursor();
        existing.setDeploymentId(deploymentId);
        existing.setIndexerType(IndexerState.IndexerType.STELLAR_HORIZON);
        existing.setCursorValue("500");
        cursors.put(deploymentId, existing);

        mockServer.expect(requestTo(operationsUrl(ISSUER, "500")))
                .andRespond(withSuccess("""
                    {"_embedded":{"records":[
                      {"id":"900","paging_token":"900","type":"account_merge","transaction_hash":"tx-a"}
                    ]}}
                    """, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(operationsUrl(issuer2, "0")))
                .andRespond(withSuccess("""
                    {"_embedded":{"records":[
                      {"id":"7","paging_token":"7","type":"account_merge","transaction_hash":"tx-b"}
                    ]}}
                    """, MediaType.APPLICATION_JSON));

        service.syncChain(chain);

        assertThat(cursors.get(deploymentId).getCursorValue()).isEqualTo("900");
        assertThat(cursors.get(deployment2).getCursorValue()).isEqualTo("7");
        mockServer.verify();
    }

    @Test
    @DisplayName("a failing issuer feed keeps its own cursor, the others advance, and the failure is recorded")
    void syncChain_failingIssuerDoesNotAdvanceItsCursor() {
        stubEmptyIndexerState();
        ChainConfig chain = stellarChain();
        String issuer2 = "GISSUER2CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
        UUID deployment2 = UUID.randomUUID();
        AssetDeployment d2 = new AssetDeployment();
        d2.setId(deployment2);
        d2.setAssetId(UUID.randomUUID());
        d2.setContractAddress(issuer2);
        when(assetDeploymentRepository.findByChainAndNetwork(Chain.STELLAR, Network.TESTNET))
                .thenReturn(List.of(deployment(), d2));

        mockServer.expect(requestTo(operationsUrl(ISSUER, "0")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());
        mockServer.expect(requestTo(operationsUrl(issuer2, "0")))
                .andRespond(withSuccess("""
                    {"_embedded":{"records":[
                      {"id":"7","paging_token":"7","type":"account_merge","transaction_hash":"tx-b"}
                    ]}}
                    """, MediaType.APPLICATION_JSON));

        service.syncChain(chain);

        assertThat(cursors).doesNotContainKey(deploymentId);
        assertThat(cursors.get(deployment2).getCursorValue()).isEqualTo("7");
        assertThat(recordedFailures).hasSize(1);
        mockServer.verify();
    }
}
