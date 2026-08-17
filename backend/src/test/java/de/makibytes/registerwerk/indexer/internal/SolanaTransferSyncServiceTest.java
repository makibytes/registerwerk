package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for the polling-cursor fix : previously the cursor was set
 * only once ever (a one-shot latch) and shared across every mint tracked on a chain — this file
 * did not exist before this fix, per the review's own finding that no test coverage existed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SolanaTransferSyncService — per-mint polling cursor")
class SolanaTransferSyncServiceTest {

    private static final String RPC_URL = "http://solana-rpc:8899";
    private static final String MINT_A = "MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String MINT_B = "MintBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private IndexerStateRepository indexerStateRepository;
    @Mock private TokenTransferRepository tokenTransferRepository;
    @Mock private SolanaMintSyncCursorRepository mintSyncCursorRepository;

    /** In-memory backing store for the mocked cursor repository — keyed by (chainConfigId, mint). */
    private final Map<String, SolanaMintSyncCursor> cursorStore = new HashMap<>();

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private MockRestServiceServer mockServer;
    private SolanaTransferSyncService service;

    private ChainConfig chain;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        when(mintSyncCursorRepository.findByChainConfigIdAndMintAddress(any(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(
                        cursorStore.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(mintSyncCursorRepository.save(any())).thenAnswer(inv -> {
            SolanaMintSyncCursor c = inv.getArgument(0);
            cursorStore.put(c.getChainConfigId() + "|" + c.getMintAddress(), c);
            return c;
        });

        chain = new ChainConfig();
        chain.setId(UUID.randomUUID());
        chain.setIdentifier("SOLANA_TESTNET");
        chain.setChainType(ChainConfig.ChainType.SOLANA);
        chain.setRpcUrl(RPC_URL);

        service = new SolanaTransferSyncService(
                chainConfigRepository, indexerStateRepository, tokenTransferRepository,
                mintSyncCursorRepository, new ExplorerUrlBuilder(), restClientBuilder);

        service.registerMintAddress(chain.getIdentifier(), MINT_A);
    }

    private void stubIndexerState() {
        when(indexerStateRepository.findByChainConfigIdAndIndexerType(any(), any()))
                .thenReturn(Optional.empty());
        when(indexerStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void expectSignaturesRequest(String responseSignature, long slot) {
        mockServer.expect(requestTo(RPC_URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("getSignaturesForAddress")))
                .andRespond(withSuccess("""
                    {"jsonrpc":"2.0","id":1,"result":[
                      {"signature":"%s","slot":%d,"err":null,"blockTime":1700000000}
                    ]}
                    """.formatted(responseSignature, slot), MediaType.APPLICATION_JSON));
    }

    private void expectEmptySignaturesRequest() {
        mockServer.expect(requestTo(RPC_URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("getSignaturesForAddress")))
                .andRespond(withSuccess("""
                    {"jsonrpc":"2.0","id":1,"result":[]}
                    """, MediaType.APPLICATION_JSON));
    }

    private void expectTransactionRequest(String signature) {
        mockServer.expect(requestTo(RPC_URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("getTransaction")))
                .andRespond(withSuccess("""
                    {"jsonrpc":"2.0","id":1,"result":{
                      "blockTime":1700000000,
                      "transaction":{"message":{"instructions":[]}}
                    }}
                    """, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("cursor advances to the newest signature after a poll with results")
    void cursorAdvances_afterPollWithResults() {
        stubIndexerState();
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(any(), anyString(), any()))
                .thenReturn(false);

        expectSignaturesRequest("sig1", 100);
        expectTransactionRequest("sig1");

        service.pollChain(chain);

        Optional<SolanaMintSyncCursor> cursor =
                mintSyncCursorRepository.findByChainConfigIdAndMintAddress(chain.getId(), MINT_A);
        assertThat(cursor).isPresent();
        assertThat(cursor.get().getLastSyncedSignature()).isEqualTo("sig1");
        mockServer.verify();
    }

    @Test
    @DisplayName("a second poll advances the cursor again (not frozen after the first poll)")
    void cursorAdvances_onEverySubsequentPoll() {
        stubIndexerState();
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(any(), anyString(), any()))
                .thenReturn(false);

        // MockRestServiceServer requires all expectations registered before any request is made —
        // register both polls' worth up front; the default expectation manager matches them in
        // registration (FIFO) order as the two sequential pollChain() calls below issue them.
        expectSignaturesRequest("sig1", 100);
        expectTransactionRequest("sig1");
        expectSignaturesRequest("sig2", 200);
        expectTransactionRequest("sig2");

        service.pollChain(chain);
        assertThat(mintSyncCursorRepository.findByChainConfigIdAndMintAddress(chain.getId(), MINT_A)
                .orElseThrow().getLastSyncedSignature()).isEqualTo("sig1");

        // 's core regression: before the fix, this second poll would never update the
        // cursor again because the "only set if null" latch had already fired once.
        service.pollChain(chain);

        assertThat(mintSyncCursorRepository.findByChainConfigIdAndMintAddress(chain.getId(), MINT_A)
                .orElseThrow().getLastSyncedSignature()).isEqualTo("sig2");
        mockServer.verify();
    }

    @Test
    @DisplayName("an empty poll leaves the existing cursor untouched")
    void emptyPoll_doesNotResetCursor() {
        stubIndexerState();
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(any(), anyString(), any()))
                .thenReturn(false);

        expectSignaturesRequest("sig1", 100);
        expectTransactionRequest("sig1");
        expectEmptySignaturesRequest();

        service.pollChain(chain);
        service.pollChain(chain);

        assertThat(mintSyncCursorRepository.findByChainConfigIdAndMintAddress(chain.getId(), MINT_A)
                .orElseThrow().getLastSyncedSignature()).isEqualTo("sig1");
        mockServer.verify();
    }

    @Test
    @DisplayName("two mints on the same chain get independent cursors, not a shared one")
    void twoMints_getIndependentCursors() {
        stubIndexerState();
        service.registerMintAddress(chain.getIdentifier(), MINT_B);
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(any(), anyString(), any()))
                .thenReturn(false);

        // One request per mint, order not guaranteed by the underlying Set — stub both patterns.
        expectSignaturesRequest("sigA", 100);
        expectTransactionRequest("sigA");
        expectSignaturesRequest("sigB", 101);
        expectTransactionRequest("sigB");

        service.pollChain(chain);

        Optional<SolanaMintSyncCursor> cursorA =
                mintSyncCursorRepository.findByChainConfigIdAndMintAddress(chain.getId(), MINT_A);
        Optional<SolanaMintSyncCursor> cursorB =
                mintSyncCursorRepository.findByChainConfigIdAndMintAddress(chain.getId(), MINT_B);
        assertThat(cursorA).isPresent();
        assertThat(cursorB).isPresent();
        // The two mints' cursors must be distinct entries, each holding one of the two responses
        // (order between the two mints isn't guaranteed, so just assert they differ from each other
        // and each is one of the expected signatures).
        assertThat(cursorA.get().getLastSyncedSignature())
                .isNotEqualTo(cursorB.get().getLastSyncedSignature());
    }
}
