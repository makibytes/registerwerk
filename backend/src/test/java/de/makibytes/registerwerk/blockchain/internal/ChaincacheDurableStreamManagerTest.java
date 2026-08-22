package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.http.WebSocket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChaincacheDurableStreamManager.ChainSubscription — chaincache_subscribeDurable wire handling")
class ChaincacheDurableStreamManagerTest {

    @Mock ChainConfigRepository chainConfigRepository;
    @Mock RpcNodeRepository rpcNodeRepository;
    @Mock BlockFinalityFeed blockFinalityFeed;
    @Mock WebSocket webSocket;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChaincacheDurableStreamManager manager;
    private ChaincacheDurableStreamManager.ChainSubscription subscription;
    private final UUID chainId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        manager = new ChaincacheDurableStreamManager(chainConfigRepository, rpcNodeRepository, blockFinalityFeed,
                objectMapper, managementUrl -> Optional.empty(), "registerwerk-test", 5000,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        subscription = manager.new ChainSubscription(chainId, "registerwerk-" + chainId);
    }

    @Test
    @DisplayName("onOpen subscribes to PROVISIONAL, SAFE, and FINALIZED streams")
    void onOpen_subscribesToAllThreeStreams() {
        subscription.onOpen(webSocket);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(3)).sendText(sent.capture(), anyBoolean());
        List<String> messages = sent.getAllValues();
        assertThat(messages).anyMatch(m -> m.contains("\"stream\":\"PROVISIONAL\""));
        assertThat(messages).anyMatch(m -> m.contains("\"stream\":\"SAFE\""));
        assertThat(messages).anyMatch(m -> m.contains("\"stream\":\"FINALIZED\""));
        assertThat(messages).allMatch(m -> m.contains("chaincache_subscribeDurable"));
    }

    @Test
    @DisplayName("a BLOCK event on the SAFE subscription records a SAFE observation and acknowledges it")
    void blockEvent_onSafeSubscription_recordsObservationAndAcks() {
        subscription.onOpen(webSocket);

        // Subscribe result for request id=2 (SAFE, sent second after PROVISIONAL) — chaincache
        // assigns local subscription id "sub-1".
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"c","stream":"SAFE","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":5,"eventId":"e1","stream":"SAFE","kind":"BLOCK","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed).recordObservation(chainId, 100L, "0xabc", FinalityLevel.SAFE);
        verify(blockFinalityFeed, never()).recordRetraction(any(), anyLong(), any(), anyInt());

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(4)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).anyMatch(m -> m.contains("chaincache_ack") && m.contains("\"sequence\":5"));
    }

    @Test
    @DisplayName("a block-level RETRACTION event records a retraction at commonAncestor+1 using "
            + "the payload's replacementBlockHash — not the top-level blockHash, which is the "
            + "ORPHANED block's own hash, not a replacement")
    void retractionEvent_blockLevel_recordsRetraction() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":3,"result":{"subscription":"sub-2","consumerId":"c","stream":"FINALIZED","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-2","result":
                  {"sequence":9,"eventId":"e2","stream":"FINALIZED","kind":"RETRACTION","blockNumber":100,
                   "blockHash":"0xorphaned100",
                   "payload":{"commonAncestor":97,"orphanedBlockHash":"0xorphaned100","replacementBlockHash":"0xnewtip"},
                   "retractsEventId":"block:0xorphaned100:provisional","createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        // recordRetraction wants "the hash now at forkBlockNumber", which this event's payload
        // does not actually carry (replacementBlockHash is the reorg's new TIP, not necessarily
        // the hash at commonAncestor+1 for a multi-block-deep reorg) — see handleRetraction's own
        // javadoc for why null, not payload.replacementBlockHash, is passed.
        verify(blockFinalityFeed).recordRetraction(chainId, 98L, null, 0);
        verify(blockFinalityFeed, never()).recordObservation(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("a per-log RETRACTION event (retractsEventId starting \"log:\") is acknowledged "
            + "but not recorded — the block-level retraction for the same orphaned block already "
            + "covers it")
    void retractionEvent_logLevel_isSkipped() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-2","consumerId":"c","stream":"PROVISIONAL","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-2","result":
                  {"sequence":10,"eventId":"e3","stream":"PROVISIONAL","kind":"RETRACTION","blockNumber":100,
                   "blockHash":"0xorphaned100","payload":{"removed":true},
                   "retractsEventId":"log:0xorphaned100:0","createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed, never()).recordRetraction(any(), anyLong(), any(), anyInt());
        verify(blockFinalityFeed, never()).recordObservation(any(), anyLong(), any(), any());
        // Still acknowledged so chaincache doesn't keep redelivering it.
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(4)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).anyMatch(m -> m.contains("chaincache_ack") && m.contains("\"sequence\":10"));
    }

    @Test
    @DisplayName("a block-level RETRACTION on the PROVISIONAL subscription is recorded — chaincache "
            + "only ever retracts on a stream the orphaned block actually reached, and a block that "
            + "never got past PROVISIONAL is retracted there and nowhere else (live-verified via an "
            + "anvil_reorg drill against a stack that only subscribed to SAFE/FINALIZED: zero "
            + "durable events were delivered for a reorg of not-yet-SAFE blocks)")
    void retractionEvent_provisionalLevel_recordsRetraction() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-1","consumerId":"c","stream":"PROVISIONAL","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":3,"eventId":"e4","stream":"PROVISIONAL","kind":"RETRACTION","blockNumber":259,
                   "blockHash":"0xorphaned259",
                   "payload":{"commonAncestor":258,"orphanedBlockHash":"0xorphaned259","replacementBlockHash":"0xnewtip"},
                   "retractsEventId":"block:0xorphaned259:provisional","createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed).recordRetraction(chainId, 259L, null, 0);
        verify(blockFinalityFeed, never()).recordObservation(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("an event for an unknown subscription id is ignored, not misattributed")
    void eventForUnknownSubscription_isIgnored() {
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-unknown","result":
                  {"sequence":1,"eventId":"e1","stream":"SAFE","kind":"BLOCK","blockNumber":1,
                   "blockHash":"0x1","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed, never()).recordObservation(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("a fragmented message is only processed once the final frame arrives")
    void fragmentedMessage_processedOnlyOnceComplete() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"c","stream":"SAFE\
                """, false);
        subscription.onText(webSocket, """
                ","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":1,"eventId":"e1","stream":"SAFE","kind":"BLOCK","blockNumber":42,
                   "blockHash":"0xfrag","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed).recordObservation(chainId, 42L, "0xfrag", FinalityLevel.SAFE);
    }

    @Test
    @DisplayName("selectNode prefers a healthy node over an unhealthy one, regardless of lag")
    void selectNode_prefersHealthy() {
        de.makibytes.registerwerk.chain.api.RpcNode healthy = node(true, 50, "2026-01-02T00:00:00Z");
        de.makibytes.registerwerk.chain.api.RpcNode unhealthyLowLag = node(false, 0, "2026-01-01T00:00:00Z");

        Optional<de.makibytes.registerwerk.chain.api.RpcNode> selected =
                ChaincacheDurableStreamManager.selectNode(List.of(unhealthyLowLag, healthy), null);

        assertThat(selected).contains(healthy);
    }

    @Test
    @DisplayName("selectNode breaks a health tie on the lowest lagFromBest")
    void selectNode_tieBreaksOnLag() {
        de.makibytes.registerwerk.chain.api.RpcNode highLag = node(true, 10, "2026-01-01T00:00:00Z");
        de.makibytes.registerwerk.chain.api.RpcNode lowLag = node(true, 0, "2026-01-02T00:00:00Z");

        Optional<de.makibytes.registerwerk.chain.api.RpcNode> selected =
                ChaincacheDurableStreamManager.selectNode(List.of(highLag, lowLag), null);

        assertThat(selected).contains(lowLag);
    }

    @Test
    @DisplayName("selectNode excludes the node that failed on the previous tick, in favor of a sibling")
    void selectNode_excludesRecentlyFailedNode() {
        de.makibytes.registerwerk.chain.api.RpcNode failed = node(true, 0, "2026-01-02T00:00:00Z");
        de.makibytes.registerwerk.chain.api.RpcNode sibling = node(true, 5, "2026-01-01T00:00:00Z");
        org.springframework.test.util.ReflectionTestUtils.setField(failed, "id", UUID.randomUUID());

        Optional<de.makibytes.registerwerk.chain.api.RpcNode> selected =
                ChaincacheDurableStreamManager.selectNode(List.of(failed, sibling), failed.getId());

        assertThat(selected).contains(sibling);
    }

    @Test
    @DisplayName("selectNode falls back to the excluded node when it is the only candidate left")
    void selectNode_onlyCandidateIsExcluded_stillReturnsIt() {
        de.makibytes.registerwerk.chain.api.RpcNode onlyNode = node(true, 0, "2026-01-01T00:00:00Z");
        org.springframework.test.util.ReflectionTestUtils.setField(onlyNode, "id", UUID.randomUUID());

        Optional<de.makibytes.registerwerk.chain.api.RpcNode> selected =
                ChaincacheDurableStreamManager.selectNode(List.of(onlyNode), onlyNode.getId());

        assertThat(selected).contains(onlyNode);
    }

    private static de.makibytes.registerwerk.chain.api.RpcNode node(boolean healthy, int lag, String createdAt) {
        de.makibytes.registerwerk.chain.api.RpcNode node = new de.makibytes.registerwerk.chain.api.RpcNode();
        org.springframework.test.util.ReflectionTestUtils.setField(node, "id", UUID.randomUUID());
        node.setHealthy(healthy);
        node.setLagFromBest(lag);
        org.springframework.test.util.ReflectionTestUtils.setField(
                node, "createdAt", java.time.Instant.parse(createdAt));
        return node;
    }

    @Test
    @DisplayName("isOpen is false before onOpen and after onClose")
    void isOpen_reflectsLifecycle() {
        assertThat(subscription.isOpen()).isFalse();

        subscription.onOpen(webSocket);
        assertThat(subscription.isOpen()).isTrue();

        subscription.onClose(webSocket, 1000, "bye");
        assertThat(subscription.isOpen()).isFalse();
    }
}
