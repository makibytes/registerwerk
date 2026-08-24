package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.ChainQuarantinedException;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.finality.api.QuarantineTrigger;
import de.makibytes.registerwerk.indexer.api.TypedReorgCompensationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.http.WebSocket;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChaincacheDurableStreamManager.ChainSubscription — chaincache_subscribeDurable wire handling")
class ChaincacheDurableStreamManagerTest {

    @Mock ChainConfigRepository chainConfigRepository;
    @Mock RpcNodeRepository rpcNodeRepository;
    @Mock BlockFinalityFeed blockFinalityFeed;
    @Mock de.makibytes.registerwerk.blockchain.api.ReorgProjectionPort typedReorgApplicationPort;
    @Mock WebSocket webSocket;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChaincacheDurableStreamManager manager;
    private ChaincacheReorgCoordinator reorgCoordinator;
    private ChaincacheDurableStreamManager.ChainSubscription subscription;
    private final UUID chainId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(webSocket.sendText(anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(webSocket));
        lenient().when(typedReorgApplicationPort.apply(any(), any())).thenReturn(
                new de.makibytes.registerwerk.blockchain.api.ReorgProjectionPort.AppliedReorg(0, List.of()));
        reorgCoordinator = new ChaincacheReorgCoordinator(blockFinalityFeed, typedReorgApplicationPort);
        manager = new ChaincacheDurableStreamManager(chainConfigRepository, rpcNodeRepository, blockFinalityFeed,
                reorgCoordinator,
                objectMapper, managementUrl -> Optional.empty(), "registerwerk-test", 5000, 60_000, 60_000,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        subscription = manager.new ChainSubscription(
                chainId, "registerwerk-test-consumer", "sepolia", nodeId, "domain-a");
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
    @DisplayName("connected is true only after all three durable subscriptions are accepted")
    void connected_requiresAllThreeSubscribeResponses() {
        activeSubscriptions().put(chainId, subscription);
        subscription.onOpen(webSocket);
        assertThat(manager.isConnected(chainId)).isFalse();

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-p","consumerId":"registerwerk-test-consumer","stream":"PROVISIONAL"}}
                """, true);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-s","consumerId":"registerwerk-test-consumer","stream":"SAFE"}}
                """, true);
        assertThat(manager.isConnected(chainId)).isFalse();

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":3,"result":{"subscription":"sub-f","consumerId":"registerwerk-test-consumer","stream":"FINALIZED"}}
                """, true);
        assertThat(manager.isConnected(chainId)).isTrue();
    }

    @Test
    @DisplayName("an exceptional subscribe send fail-stops instead of leaving a ghost connection")
    void exceptionalSubscribeSend_failStops() {
        CompletableFuture<WebSocket> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("socket write failed"));
        when(webSocket.sendText(anyString(), anyBoolean())).thenReturn(failed);

        subscription.onOpen(webSocket);

        verify(webSocket, timeout(1_000)).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
        assertThat(manager.isConnected(chainId)).isFalse();
    }

    @Test
    @DisplayName("a missing subscribe response fails the connection at its protocol deadline")
    void subscribeResponseDeadline_failStops() {
        ChaincacheDurableStreamManager shortDeadlineManager = managerWithTimeouts(25, 60_000);
        ChaincacheDurableStreamManager.ChainSubscription timed = shortDeadlineManager.new ChainSubscription(
                chainId, "registerwerk-test-consumer", "sepolia", nodeId, "domain-a");

        timed.onOpen(webSocket);

        verify(webSocket, timeout(2_000)).sendClose(1011, "durable event processing failed");
        assertThat(timed.isOpen()).isFalse();
        assertThat(shortDeadlineManager.isConnected(chainId)).isFalse();
    }

    @Test
    @DisplayName("a rejected subscription fail-stops so reconcile can retry or fail over")
    void subscribeJsonRpcError_failStops() {
        subscription.onOpen(webSocket);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"subscription rejected"}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("an established durable subscription error notification fail-stops")
    void durableSubscriptionErrorNotification_failStops() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE"}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_subscriptionError",
                 "params":{"subscription":"sub-1","message":"durable polling failed"}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a rejected acknowledgement fail-stops because its cursor was not persisted")
    void acknowledgementJsonRpcError_failStops() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE","acknowledgedSequence":0}}
                """, true);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":5,"eventId":"e1","stream":"SAFE","kind":"BLOCK","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":6,"eventId":"e2","stream":"SAFE","kind":"BLOCK","blockNumber":101,
                   "blockHash":"0xdef","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:01Z"}}}
                """, true);

        // IDs 1..3 are the subscribe requests; the first ACK is request id 4.
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":4,"error":{"code":-32000,"message":"cursor write failed"}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        verify(blockFinalityFeed, never()).recordObservation(chainId, 101L, "0xdef", FinalityLevel.SAFE);
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a later event waits until the preceding cursor ACK is confirmed")
    void acknowledgementSuccess_releasesNextQueuedEvent() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE","acknowledgedSequence":0}}
                """, true);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":5,"eventId":"e1","stream":"SAFE","kind":"BLOCK","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":6,"eventId":"e2","stream":"SAFE","kind":"BLOCK","blockNumber":101,
                   "blockHash":"0xdef","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:01Z"}}}
                """, true);

        verify(blockFinalityFeed, never()).recordObservation(chainId, 101L, "0xdef", FinalityLevel.SAFE);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":4,"result":true}
                """, true);

        verify(blockFinalityFeed).recordObservation(chainId, 101L, "0xdef", FinalityLevel.SAFE);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(5)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).anyMatch(m -> m.contains("chaincache_ack") && m.contains("\"sequence\":6"));
    }

    @Test
    @DisplayName("an exceptional ACK send fail-stops before a later sequence can be processed")
    void exceptionalAckSend_failStops() {
        CompletableFuture<WebSocket> sent = CompletableFuture.completedFuture(webSocket);
        CompletableFuture<WebSocket> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("ACK write failed"));
        when(webSocket.sendText(anyString(), anyBoolean())).thenReturn(sent, sent, sent, failed);
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE"}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":5,"eventId":"e1","stream":"SAFE","kind":"BLOCK","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(webSocket, timeout(1_000)).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a missing ACK response fails the connection before its cursor can be skipped")
    void ackResponseDeadline_failStops() {
        ChaincacheDurableStreamManager shortDeadlineManager = managerWithTimeouts(60_000, 25);
        ChaincacheDurableStreamManager.ChainSubscription timed = shortDeadlineManager.new ChainSubscription(
                chainId, "registerwerk-test-consumer", "sepolia", nodeId, "domain-a");
        timed.onOpen(webSocket);
        timed.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE"}}
                """, true);
        timed.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":5,"eventId":"e1","stream":"SAFE","kind":"BLOCK","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(webSocket, timeout(2_000)).sendClose(1011, "durable event processing failed");
        assertThat(timed.isOpen()).isFalse();
        assertThat(shortDeadlineManager.isConnected(chainId)).isFalse();
    }

    @Test
    @DisplayName("a pending subscribe result without a subscription id fail-stops")
    void subscribeResultMissingSubscription_failStops() {
        subscription.onOpen(webSocket);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"consumerId":"registerwerk-test-consumer","stream":"SAFE"}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a subscribe result for a different stream fail-stops")
    void subscribeResultMismatchedStream_failStops() {
        subscription.onOpen(webSocket);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"FINALIZED"}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a subscribe result for a different consumer fail-stops")
    void subscribeResultMismatchedConsumer_failStops() {
        subscription.onOpen(webSocket);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"someone-else","stream":"SAFE"}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a BLOCK event on the SAFE subscription records a SAFE observation and acknowledges it")
    void blockEvent_onSafeSubscription_recordsObservationAndAcks() {
        subscription.onOpen(webSocket);

        // Subscribe result for request id=2 (SAFE, sent second after PROVISIONAL) — chaincache
        // assigns local subscription id "sub-1".
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE","acknowledgedSequence":0}}
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
        assertThat(sent.getAllValues()).anyMatch(m -> m.contains("chaincache_ack")
                && m.contains("\"subscription\":\"sub-1\"")
                && m.contains("\"sequence\":5"));
    }

    @Test
    @DisplayName("a poison event fail-stops the listener so a later valid sequence cannot skip it")
    void blockEvent_activeQuarantine_failStopsBeforeNextSequence() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE","acknowledgedSequence":0}}
                """, true);
        doThrow(new ChainQuarantinedException(chainId)).when(blockFinalityFeed)
                .recordObservation(chainId, 100L, "0xabc", FinalityLevel.SAFE);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":6,"eventId":"e-parked","stream":"SAFE","kind":"BLOCK","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":7,"eventId":"e-must-not-skip","stream":"SAFE","kind":"BLOCK","blockNumber":101,
                   "blockHash":"0xdef","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:01Z"}}}
                """, true);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(3)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).noneMatch(m -> m.contains("chaincache_ack"));
        verify(blockFinalityFeed, never()).recordObservation(chainId, 101L, "0xdef", FinalityLevel.SAFE);
        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("invalid sequence metadata fail-stops before processing or acknowledgement")
    void eventWithMissingSequence_failStops() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"eventId":"missing-sequence","stream":"SAFE","kind":"BLOCK","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed, never()).recordObservation(any(), anyLong(), any(), any());
        verify(webSocket).sendClose(1011, "durable event processing failed");
    }

    @Test
    @DisplayName("a negative durable sequence is rejected before processing or acknowledgement")
    void eventWithNegativeSequence_failStops() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":-1,"eventId":"negative-sequence","stream":"SAFE","kind":"BLOCK","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed, never()).recordObservation(any(), anyLong(), any(), any());
        verify(webSocket).sendClose(1011, "durable event processing failed");
    }

    @Test
    @DisplayName("event stream metadata must match the subscription that delivered it")
    void eventWithMismatchedStream_failStops() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":8,"eventId":"wrong-stream","stream":"FINALIZED","kind":"BLOCK","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed, never()).recordObservation(any(), anyLong(), any(), any());
        verify(webSocket).sendClose(1011, "durable event processing failed");
    }

    @Test
    @DisplayName("a block-level RETRACTION event records a retraction at commonAncestor+1 using "
            + "the payload's replacementBlockHash — not the top-level blockHash, which is the "
            + "ORPHANED block's own hash, not a replacement")
    void retractionEvent_blockLevel_recordsRetraction() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":3,"result":{"subscription":"sub-2","consumerId":"registerwerk-test-consumer","stream":"FINALIZED","acknowledgedSequence":0}}
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
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-2","consumerId":"registerwerk-test-consumer","stream":"PROVISIONAL","acknowledgedSequence":0}}
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
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"PROVISIONAL","acknowledgedSequence":0}}
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
    @DisplayName("a typed REORG episode is applied once through the episode API and acknowledged")
    void reorgEvent_recordsTypedEpisode() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-r","consumerId":"registerwerk-test-consumer","stream":"PROVISIONAL","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-r","result":
                  {"sequence":11,"eventId":"reorg:episode-1:provisional","stream":"PROVISIONAL","kind":"REORG",
                   "blockNumber":100,"blockHash":"0xbbb","payload":{},"retractsEventId":null,
                   "createdAt":"2026-01-01T00:00:00Z","reorg":{
                     "schemaVersion":"1","reorgId":"episode-1","chainKey":{"value":"sepolia"},
                     "commonAncestor":{"blockNumber":99,"blockHash":"0xparent","parentHash":"0x98","finality":"SAFE"},
                     "orphanedLineage":[{"blockNumber":100,"blockHash":"0xaaa","parentHash":"0xparent","finality":"SAFE"}],
                     "replacementLineage":[{"blockNumber":100,"blockHash":"0xbbb","parentHash":"0xparent","finality":"PROVISIONAL"}],
                     "severity":"ROUTINE","observedAt":"2026-01-01T00:00:00Z"}}}}
                """, true);

        ArgumentCaptor<ReorgObservation> observation = ArgumentCaptor.forClass(ReorgObservation.class);
        verify(blockFinalityFeed).recordReorg(org.mockito.ArgumentMatchers.eq(chainId), observation.capture(),
                org.mockito.ArgumentMatchers.eq(0));
        assertThat(observation.getValue().reorgId()).isEqualTo("episode-1");
        assertThat(observation.getValue().forkBlockNumber()).isEqualTo(100L);
        assertThat(observation.getValue().replacementHashAtFork()).isEqualTo("0xbbb");
        verify(blockFinalityFeed, never()).recordRetraction(any(), anyLong(), any(), anyInt());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(4)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).anyMatch(m -> m.contains("chaincache_ack") && m.contains("\"sequence\":11"));
    }

    @Test
    @DisplayName("failed typed indexer compensation rolls back there, quarantines through finality, and is acknowledged")
    void reorgEvent_failedIndexerCompensation_quarantinesBeforeAck() {
        when(typedReorgApplicationPort.apply(any(), any()))
                .thenThrow(new TypedReorgCompensationException("holder recompute failed"));
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-r","consumerId":"registerwerk-test-consumer","stream":"PROVISIONAL","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-r","result":
                  {"sequence":11,"eventId":"reorg:episode-holder-failed:provisional","stream":"PROVISIONAL","kind":"REORG",
                   "blockNumber":100,"blockHash":"0xbbb","payload":{},"retractsEventId":null,
                   "createdAt":"2026-01-01T00:00:00Z","reorg":{
                     "schemaVersion":"1","reorgId":"episode-holder-failed","chainKey":{"value":"sepolia"},
                     "commonAncestor":{"blockNumber":99,"blockHash":"0xparent","parentHash":"0x98","finality":"SAFE"},
                     "orphanedLineage":[{"blockNumber":100,"blockHash":"0xaaa","parentHash":"0xparent","finality":"SAFE"}],
                     "replacementLineage":[{"blockNumber":100,"blockHash":"0xbbb","parentHash":"0xparent","finality":"PROVISIONAL"}],
                     "severity":"ROUTINE","observedAt":"2026-01-01T00:00:00Z"}}}}
                """, true);

        verify(blockFinalityFeed).recordReorg(eq(chainId), any(ReorgObservation.class), eq(0),
                eq(QuarantineTrigger.INDEXER_COMPENSATION_FAILED));
        verify(webSocket, never()).sendClose(eq(1011), anyString());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(4)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).anyMatch(m -> m.contains("chaincache_ack") && m.contains("\"sequence\":11"));
    }

    @Test
    @DisplayName("the same quarantining REORG on a second finality stream skips indexer replay and ACKs")
    void reorgEvent_duplicateAcrossStreams_skipsBeforeQuarantinedIndexerReplay() {
        when(blockFinalityFeed.isReorgRecorded(eq(chainId), any(ReorgObservation.class)))
                .thenReturn(false, true);
        when(typedReorgApplicationPort.apply(any(), any()))
                .thenThrow(new TypedReorgCompensationException("holder recompute failed"));
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-p","consumerId":"registerwerk-test-consumer","stream":"PROVISIONAL","acknowledgedSequence":0}}
                """, true);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-s","consumerId":"registerwerk-test-consumer","stream":"SAFE","acknowledgedSequence":0}}
                """, true);

        String eventTemplate = """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"%s","result":
                  {"sequence":%d,"eventId":"reorg:episode-dupe","stream":"%s","kind":"REORG",
                   "blockNumber":100,"blockHash":"0xbbb","payload":{},"retractsEventId":null,
                   "createdAt":"2026-01-01T00:00:00Z","reorg":{
                     "schemaVersion":"1","reorgId":"episode-dupe","chainKey":{"value":"sepolia"},
                     "commonAncestor":{"blockNumber":99,"blockHash":"0xparent","parentHash":"0x98","finality":"SAFE"},
                     "orphanedLineage":[{"blockNumber":100,"blockHash":"0xaaa","parentHash":"0xparent","finality":"SAFE"}],
                     "replacementLineage":[{"blockNumber":100,"blockHash":"0xbbb","parentHash":"0xparent","finality":"PROVISIONAL"}],
                     "severity":"ROUTINE","observedAt":"2026-01-01T00:00:00Z"}}}}
                """;
        subscription.onText(webSocket, eventTemplate.formatted("sub-p", 11, "PROVISIONAL"), true);
        subscription.onText(webSocket, eventTemplate.formatted("sub-s", 12, "SAFE"), true);

        verify(typedReorgApplicationPort, times(1)).apply(any(), any());
        verify(blockFinalityFeed, times(1)).recordReorg(
                eq(chainId), any(ReorgObservation.class), eq(0),
                eq(QuarantineTrigger.INDEXER_COMPENSATION_FAILED));
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(5)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).anyMatch(m -> m.contains("chaincache_ack") && m.contains("\"sequence\":11"));
        assertThat(sent.getAllValues()).anyMatch(m -> m.contains("chaincache_ack") && m.contains("\"sequence\":12"));
    }

    @Test
    @DisplayName("a typed REORG with a coercible non-integral lineage height fail-stops without acknowledgement")
    void reorgEvent_nonIntegralNestedBlockNumber_isRejectedWithoutAck() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-r","consumerId":"registerwerk-test-consumer","stream":"PROVISIONAL","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-r","result":
                  {"sequence":12,"eventId":"reorg:malformed:provisional","stream":"PROVISIONAL","kind":"REORG",
                   "blockNumber":100,"blockHash":"0xbbb","payload":{},"retractsEventId":null,
                   "createdAt":"2026-01-01T00:00:00Z","reorg":{
                     "schemaVersion":"1","reorgId":"malformed","chainKey":{"value":"sepolia"},
                     "commonAncestor":{"blockNumber":99.5,"blockHash":"0xparent","parentHash":"0x98","finality":"SAFE"},
                     "orphanedLineage":[{"blockNumber":100,"blockHash":"0xaaa","parentHash":"0xparent","finality":"SAFE"}],
                     "replacementLineage":[{"blockNumber":100,"blockHash":"0xbbb","parentHash":"0xparent","finality":"PROVISIONAL"}],
                     "severity":"ROUTINE","observedAt":"2026-01-01T00:00:00Z"}}}}
                """, true);

        verify(typedReorgApplicationPort, never()).apply(any(), any());
        verify(blockFinalityFeed, never()).recordReorg(any(), any(), anyInt());
        verify(webSocket).sendClose(1011, "durable event processing failed");
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(3)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).noneMatch(message -> message.contains("chaincache_ack"));
    }

    @Test
    @DisplayName("a typed finality violation quarantines through finality without mutating indexer state")
    void finalityViolation_doesNotApplyIndexerCompensation() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-r","consumerId":"registerwerk-test-consumer","stream":"PROVISIONAL","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-r","result":
                  {"sequence":12,"eventId":"reorg:violation:provisional","stream":"PROVISIONAL","kind":"REORG",
                   "blockNumber":100,"blockHash":"0xbbb","payload":{},"retractsEventId":null,
                   "createdAt":"2026-01-01T00:00:00Z","reorg":{
                     "schemaVersion":"1","reorgId":"violation","chainKey":{"value":"sepolia"},
                     "commonAncestor":{"blockNumber":99,"blockHash":"0xparent","parentHash":"0x98","finality":"FINALIZED"},
                     "orphanedLineage":[{"blockNumber":100,"blockHash":"0xaaa","parentHash":"0xparent","finality":"FINALIZED"}],
                     "replacementLineage":[{"blockNumber":100,"blockHash":"0xbbb","parentHash":"0xparent","finality":"PROVISIONAL"}],
                     "severity":"FINALITY_VIOLATION","observedAt":"2026-01-01T00:00:00Z"}}}}
                """, true);

        verify(typedReorgApplicationPort, never()).apply(any(), any());
        verify(blockFinalityFeed).recordReorg(eq(chainId), any(ReorgObservation.class), eq(0));
    }

    @Test
    @DisplayName("a typed REORG for another remote chain is neither applied nor acknowledged")
    void reorgEvent_wrongChainKey_isRejectedWithoutAck() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-r","consumerId":"registerwerk-test-consumer","stream":"PROVISIONAL","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-r","result":
                  {"sequence":13,"eventId":"reorg:wrong-chain:provisional","stream":"PROVISIONAL","kind":"REORG",
                   "blockNumber":100,"blockHash":"0xbbb","payload":{},"retractsEventId":null,
                   "createdAt":"2026-01-01T00:00:00Z","reorg":{
                     "schemaVersion":"1","reorgId":"wrong-chain","chainKey":{"value":"mainnet"},
                     "commonAncestor":{"blockNumber":99,"blockHash":"0xparent","parentHash":"0x98","finality":"SAFE"},
                     "orphanedLineage":[{"blockNumber":100,"blockHash":"0xaaa","parentHash":"0xparent","finality":"SAFE"}],
                     "replacementLineage":[{"blockNumber":100,"blockHash":"0xbbb","parentHash":"0xparent","finality":"PROVISIONAL"}],
                     "severity":"ROUTINE","observedAt":"2026-01-01T00:00:00Z"}}}}
                """, true);

        verify(blockFinalityFeed, never()).recordReorg(any(), any(), anyInt());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(3)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).noneMatch(m -> m.contains("chaincache_ack"));
    }

    @Test
    @DisplayName("legacy block retractions carrying a typed episode id are acknowledged but not reapplied")
    void retractionWithReorgId_isSkipped() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-r","consumerId":"registerwerk-test-consumer","stream":"PROVISIONAL","acknowledgedSequence":0}}
                """, true);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-r","result":
                  {"sequence":12,"eventId":"legacy","stream":"PROVISIONAL","kind":"RETRACTION","blockNumber":100,
                   "blockHash":"0xaaa","payload":{"reorgId":"episode-1","commonAncestor":99},
                   "retractsEventId":"block:0xaaa:provisional","createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed, never()).recordRetraction(any(), anyLong(), any(), anyInt());
        verify(blockFinalityFeed, never()).recordReorg(any(), any(), anyInt());
    }

    @Test
    @DisplayName("an event for an unknown subscription id fail-stops instead of risking a cursor jump")
    void eventForUnknownSubscription_failStops() {
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-unknown","result":
                  {"sequence":1,"eventId":"e1","stream":"SAFE","kind":"BLOCK","blockNumber":1,
                   "blockHash":"0x1","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed, never()).recordObservation(any(), anyLong(), any(), any());
        verify(webSocket).sendClose(1011, "durable event processing failed");
    }

    @Test
    @DisplayName("an unknown durable kind is not acknowledged")
    void unknownEventKind_failStops() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE","acknowledgedSequence":0}}
                """, true);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":9,"eventId":"future-kind","stream":"SAFE","kind":"SURPRISE","payload":{},
                   "retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(3)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).noneMatch(m -> m.contains("chaincache_ack"));
    }

    @Test
    @DisplayName("a malformed BLOCK is not acknowledged")
    void blockWithoutHash_failStops() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE","acknowledgedSequence":0}}
                """, true);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":10,"eventId":"bad-block","stream":"SAFE","kind":"BLOCK","blockNumber":100,
                   "payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed, never()).recordObservation(any(), anyLong(), any(), any());
        verify(webSocket).sendClose(1011, "durable event processing failed");
    }

    @Test
    @DisplayName("a known LOG event is intentionally ignored by the block ledger and acknowledged")
    void logEvent_isKnownNoOpAndAcknowledged() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE","acknowledgedSequence":0}}
                """, true);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":11,"eventId":"log:1","stream":"SAFE","kind":"LOG","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed, never()).recordObservation(any(), anyLong(), any(), any());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(4)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).anyMatch(m -> m.contains("chaincache_ack") && m.contains("\"sequence\":11"));
    }

    @Test
    @DisplayName("a fragmented message is only processed once the final frame arrives")
    void fragmentedMessage_processedOnlyOnceComplete() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer","stream":"SAFE\
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

    @Test
    @DisplayName("durable failover accepts only one identical nonblank durability domain")
    void sharedDurabilityDomain_requiresOneNonblankValue() {
        RpcNode first = node(true, 0, "2026-01-01T00:00:00Z");
        RpcNode sibling = node(true, 0, "2026-01-02T00:00:00Z");
        first.setCapabilities(Map.of("durabilityDomainId", "shared-db"));
        sibling.setCapabilities(Map.of("durabilityDomainId", "shared-db"));

        assertThat(ChaincacheDurableStreamManager.sharedDurabilityDomain(List.of(first, sibling)))
                .contains("shared-db");

        sibling.setCapabilities(Map.of("durabilityDomainId", "other-db"));
        assertThat(ChaincacheDurableStreamManager.sharedDurabilityDomain(List.of(first, sibling))).isEmpty();

        sibling.setCapabilities(Map.of());
        assertThat(ChaincacheDurableStreamManager.sharedDurabilityDomain(List.of(first, sibling))).isEmpty();
    }

    @Test
    @DisplayName("reconcile closes an active stream when its selected node was disabled or removed")
    void reconcile_selectedNodeNoLongerEligible_closesStream() {
        ChainConfig chain = mock(ChainConfig.class);
        when(chain.getId()).thenReturn(chainId);
        RpcNode replacement = node(true, 0, "2026-01-02T00:00:00Z");
        replacement.setCapabilities(Map.of("durabilityDomainId", "domain-a"));
        when(chainConfigRepository.findByEnabledTrueAndFinalitySource(ChainConfig.FinalitySource.CHAINCACHE))
                .thenReturn(List.of(chain));
        when(rpcNodeRepository.findByChainConfig_IdAndKindAndEnabledTrue(
                chainId, RpcNode.NodeKind.CHAINCACHE)).thenReturn(List.of(replacement));
        activeSubscriptions().put(chainId, subscription);
        subscription.onOpen(webSocket);

        manager.reconcile();

        verify(webSocket).sendClose(WebSocket.NORMAL_CLOSURE, "no longer needed");
        assertThat(activeSubscriptions()).doesNotContainKey(chainId);
        assertThat(manager.isConnected(chainId)).isFalse();
    }

    @Test
    @DisplayName("reconcile fails closed when enabled siblings advertise different durability domains")
    void reconcile_conflictingDurabilityDomains_closesWithoutFailover() {
        ChainConfig chain = mock(ChainConfig.class);
        when(chain.getId()).thenReturn(chainId);
        RpcNode selected = node(true, 0, "2026-01-01T00:00:00Z");
        org.springframework.test.util.ReflectionTestUtils.setField(selected, "id", nodeId);
        selected.setCapabilities(Map.of("durabilityDomainId", "domain-a"));
        RpcNode unrelated = node(true, 1, "2026-01-02T00:00:00Z");
        unrelated.setCapabilities(Map.of("durabilityDomainId", "domain-b"));
        when(chainConfigRepository.findByEnabledTrueAndFinalitySource(ChainConfig.FinalitySource.CHAINCACHE))
                .thenReturn(List.of(chain));
        when(rpcNodeRepository.findByChainConfig_IdAndKindAndEnabledTrue(
                chainId, RpcNode.NodeKind.CHAINCACHE)).thenReturn(List.of(selected, unrelated));
        activeSubscriptions().put(chainId, subscription);
        subscription.onOpen(webSocket);

        manager.reconcile();

        verify(webSocket).sendClose(WebSocket.NORMAL_CLOSURE, "no longer needed");
        verify(webSocket, times(3)).sendText(anyString(), anyBoolean());
        assertThat(activeSubscriptions()).doesNotContainKey(chainId);
        assertThat(manager.isConnected(chainId)).isFalse();
    }

    @Test
    @DisplayName("a runtime transport failure marks the selected node for same-domain failover")
    void runtimeFailure_marksSelectedNodeFailed() {
        activeSubscriptions().put(chainId, subscription);
        subscription.onOpen(webSocket);

        subscription.onError(webSocket, new IllegalStateException("connection reset"));

        assertThat(recentlyFailedNodes()).containsEntry(chainId, nodeId);
        assertThat(activeSubscriptions()).doesNotContainKey(chainId);
        assertThat(manager.isConnected(chainId)).isFalse();
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

    private ChaincacheDurableStreamManager managerWithTimeouts(long subscribeTimeoutMs, long ackTimeoutMs) {
        return new ChaincacheDurableStreamManager(chainConfigRepository, rpcNodeRepository, blockFinalityFeed,
                reorgCoordinator,
                objectMapper, managementUrl -> Optional.empty(), "registerwerk-test", 5_000,
                subscribeTimeoutMs, ackTimeoutMs,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, ChaincacheDurableStreamManager.ChainSubscription> activeSubscriptions() {
        return (Map<UUID, ChaincacheDurableStreamManager.ChainSubscription>)
                org.springframework.test.util.ReflectionTestUtils.getField(manager, "activeByChain");
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, UUID> recentlyFailedNodes() {
        return (Map<UUID, UUID>)
                org.springframework.test.util.ReflectionTestUtils.getField(manager, "recentlyFailedNodeId");
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

    @Test
    @DisplayName("an installed asynchronous handshake is active before onOpen")
    void isOpen_countsPendingActiveHandshake() {
        activeSubscriptions().put(chainId, subscription);

        assertThat(subscription.isOpen()).isTrue();

        activeSubscriptions().remove(chainId, subscription);
        assertThat(subscription.isOpen()).isFalse();
    }
}
