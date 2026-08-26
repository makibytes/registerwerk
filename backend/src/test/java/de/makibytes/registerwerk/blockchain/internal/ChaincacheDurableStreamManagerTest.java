package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Wire-level coverage of {@link ChaincacheDurableStreamManager.ChainSubscription}: framing,
 * {@code chaincache_subscribeLifecycle}/{@code chaincache_ackLifecycle} sequencing, response
 * deadlines, fail-stop behavior, and node selection/reconcile. Business-logic dispatch (BLOCK/LOG/
 * REORG/RETRACTION handling, quarantine, typed episode application) now lives entirely in
 * {@link ChaincacheLifecycleEventProcessor} — that gets its own dedicated tests; here
 * {@code lifecycleProcessor} and {@code lifecycleFailureRecorder} are mocked so these tests stay
 * focused on the transport contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChaincacheDurableStreamManager.ChainSubscription — chaincache_subscribeLifecycle wire handling")
class ChaincacheDurableStreamManagerTest {

    @Mock ChainConfigRepository chainConfigRepository;
    @Mock RpcNodeRepository rpcNodeRepository;
    @Mock ChaincacheLifecycleEventProcessor lifecycleProcessor;
    @Mock ChaincacheLifecycleFailureRecorder lifecycleFailureRecorder;
    @Mock WebSocket webSocket;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChaincacheDurableStreamManager manager;
    private ChaincacheDurableStreamManager.ChainSubscription subscription;
    private final UUID chainId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(webSocket.sendText(anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(webSocket));
        manager = new ChaincacheDurableStreamManager(chainConfigRepository, rpcNodeRepository,
                lifecycleProcessor, lifecycleFailureRecorder,
                objectMapper, managementUrl -> Optional.empty(), "registerwerk-test", 5000, 60_000, 60_000,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        manager.start();
        subscription = manager.new ChainSubscription(
                chainId, "registerwerk-test-consumer", "sepolia", nodeId, "domain-a");
    }

    @Test
    @DisplayName("onOpen sends chaincache_subscribeLifecycle with this connection's consumerId")
    void onOpen_subscribesToLifecycleStream() {
        subscription.onOpen(webSocket);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getValue()).contains("chaincache_subscribeLifecycle");
        assertThat(sent.getValue()).contains("\"consumerId\":\"registerwerk-test-consumer\"");
    }

    @Test
    @DisplayName("connected is true only after the lifecycle subscribe response is accepted")
    void connected_requiresSubscribeResponse() {
        activeSubscriptions().put(chainId, subscription);
        subscription.onOpen(webSocket);
        assertThat(manager.isConnected(chainId)).isFalse();

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer",
                 "acknowledgedSequence":0,"startSequence":0,"schemaVersion":"2","durabilityDomainId":"domain-a",
                 "chainKey":"sepolia"}}
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
    @DisplayName("a subscribe result missing the subscription id fail-stops")
    void subscribeResultMissingSubscription_failStops() {
        subscription.onOpen(webSocket);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"consumerId":"registerwerk-test-consumer",
                 "schemaVersion":"2","durabilityDomainId":"domain-a","chainKey":"sepolia"}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a subscribe result for a different consumer fail-stops")
    void subscribeResultMismatchedConsumer_failStops() {
        subscription.onOpen(webSocket);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-1","consumerId":"someone-else",
                 "schemaVersion":"2","durabilityDomainId":"domain-a","chainKey":"sepolia"}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a subscribe result for a different durability domain fail-stops")
    void subscribeResultMismatchedDomain_failStops() {
        subscription.onOpen(webSocket);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer",
                 "schemaVersion":"2","durabilityDomainId":"other-domain","chainKey":"sepolia"}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a subscribe result for a different chain key fail-stops")
    void subscribeResultMismatchedChainKey_failStops() {
        subscription.onOpen(webSocket);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer",
                 "schemaVersion":"2","durabilityDomainId":"domain-a","chainKey":"base"}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("an established lifecycle subscription error notification fail-stops")
    void lifecycleSubscriptionErrorNotification_failStops() {
        subscribeSuccessfully();

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_subscriptionError",
                 "params":{"subscription":"sub-1","message":"durable polling failed"}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a lifecycle event is handed to the processor and acknowledged on success")
    void lifecycleEvent_processedAndAcknowledged() {
        subscribeSuccessfully();

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":5,"eventId":"e1","kind":"BLOCK","finality":"SAFE","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        ArgumentCaptor<tools.jackson.databind.JsonNode> eventCaptor =
                ArgumentCaptor.forClass(tools.jackson.databind.JsonNode.class);
        verify(lifecycleProcessor).process(org.mockito.ArgumentMatchers.eq(chainId),
                org.mockito.ArgumentMatchers.eq("registerwerk-test-consumer"),
                org.mockito.ArgumentMatchers.eq("sepolia"), org.mockito.ArgumentMatchers.eq("domain-a"),
                eventCaptor.capture());
        assertThat(eventCaptor.getValue().path("eventId").asText()).isEqualTo("e1");
        verifyNoInteractions(lifecycleFailureRecorder);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(2)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).anyMatch(m -> m.contains("chaincache_ackLifecycle")
                && m.contains("\"subscription\":\"sub-1\"") && m.contains("\"sequence\":5"));
    }

    @Test
    @DisplayName("a second event waits until the preceding lifecycle ACK is confirmed")
    void secondEvent_queuedUntilAckConfirmed() {
        subscribeSuccessfully();

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":5,"eventId":"e1","kind":"BLOCK","finality":"SAFE","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":6,"eventId":"e2","kind":"BLOCK","finality":"SAFE","blockNumber":101,
                   "blockHash":"0xdef","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:01Z"}}}
                """, true);

        verify(lifecycleProcessor, times(1)).process(any(), any(), any(), any(), any());

        // ACK for sequence 5 is request id 2 (id 1 was the subscribe request).
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":true}
                """, true);

        verify(lifecycleProcessor, times(2)).process(any(), any(), any(), any(), any());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(3)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).anyMatch(m -> m.contains("chaincache_ackLifecycle")
                && m.contains("\"sequence\":6"));
    }

    @Test
    @DisplayName("a rejected lifecycle ACK fail-stops because its cursor was not persisted")
    void lifecycleAckJsonRpcError_failStops() {
        subscribeSuccessfully();
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":5,"eventId":"e1","kind":"BLOCK","finality":"SAFE","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"error":{"code":-32000,"message":"cursor write failed"}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("an exceptional ACK send fail-stops before a later sequence can be processed")
    void exceptionalAckSend_failStops() {
        CompletableFuture<WebSocket> sent = CompletableFuture.completedFuture(webSocket);
        CompletableFuture<WebSocket> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("ACK write failed"));
        when(webSocket.sendText(anyString(), anyBoolean())).thenReturn(sent, failed);
        subscribeSuccessfully();

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":5,"eventId":"e1","kind":"BLOCK","finality":"SAFE","blockNumber":100,
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
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer",
                 "schemaVersion":"2","durabilityDomainId":"domain-a","chainKey":"sepolia"}}
                """, true);
        timed.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":5,"eventId":"e1","kind":"BLOCK","finality":"SAFE","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(webSocket, timeout(2_000)).sendClose(1011, "durable event processing failed");
        assertThat(timed.isOpen()).isFalse();
        assertThat(shortDeadlineManager.isConnected(chainId)).isFalse();
    }

    @Test
    @DisplayName("a processing failure records the poison envelope and fail-stops without acking")
    void processingFailure_recordsAndFailStopsWithoutAck() {
        subscribeSuccessfully();
        doThrow(new ChaincacheProtocolException("lifecycle sequence gap")).when(lifecycleProcessor)
                .process(any(), any(), any(), any(), any());

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":9,"eventId":"e-poison","kind":"BLOCK","finality":"SAFE","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(lifecycleFailureRecorder).record(org.mockito.ArgumentMatchers.eq(chainId),
                org.mockito.ArgumentMatchers.eq("registerwerk-test-consumer"),
                org.mockito.ArgumentMatchers.eq("sepolia"), org.mockito.ArgumentMatchers.eq("domain-a"),
                any(), any(ChaincacheProtocolException.class));
        verify(webSocket).sendClose(1011, "durable event processing failed");
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(1)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).noneMatch(m -> m.contains("chaincache_ackLifecycle"));
    }

    @Test
    @DisplayName("a failure recorder error is suppressed onto the original failure, not swallowed")
    void processingFailure_journalFailureIsSuppressedNotSwallowed() {
        subscribeSuccessfully();
        doThrow(new ChaincacheProtocolException("lifecycle sequence gap")).when(lifecycleProcessor)
                .process(any(), any(), any(), any(), any());
        doThrow(new IllegalStateException("journal write failed")).when(lifecycleFailureRecorder)
                .record(any(), any(), any(), any(), any(), any());

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":9,"eventId":"e-poison","kind":"BLOCK","finality":"SAFE","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
        assertThat(subscription.isOpen()).isFalse();
    }

    @Test
    @DisplayName("invalid sequence metadata fail-stops without acknowledgement (the real "
            + "ChaincacheLifecycleEventProcessor validates this itself inside process(); this "
            + "mock does not, so the manager's own post-process sequence check is what fires here)")
    void eventWithMissingSequence_failStops() {
        subscribeSuccessfully();

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"eventId":"missing-sequence","kind":"BLOCK","finality":"SAFE","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(lifecycleProcessor).process(any(), any(), any(), any(), any());
        verifyNoInteractions(lifecycleFailureRecorder);
        verify(webSocket).sendClose(1011, "durable event processing failed");
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(1)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).noneMatch(m -> m.contains("chaincache_ackLifecycle"));
    }

    @Test
    @DisplayName("a negative lifecycle sequence is rejected before processing or acknowledgement")
    void eventWithNegativeSequence_failStops() {
        subscribeSuccessfully();

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":-1,"eventId":"negative-sequence","kind":"BLOCK","finality":"SAFE","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(webSocket).sendClose(1011, "durable event processing failed");
    }

    @Test
    @DisplayName("an event for an unknown subscription id fail-stops instead of risking a cursor jump")
    void eventForUnknownSubscription_failStops() {
        subscribeSuccessfully();

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-unknown","result":
                  {"sequence":1,"eventId":"e1","kind":"BLOCK","finality":"SAFE","blockNumber":1,
                   "blockHash":"0x1","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(lifecycleProcessor, never()).process(any(), any(), any(), any(), any());
        verify(webSocket).sendClose(1011, "durable event processing failed");
    }

    @Test
    @DisplayName("a fragmented message is only processed once the final frame arrives")
    void fragmentedMessage_processedOnlyOnceComplete() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer\
                """, false);
        subscription.onText(webSocket, """
                ","schemaVersion":"2","durabilityDomainId":"domain-a","chainKey":"sepolia"}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":1,"eventId":"e1","kind":"BLOCK","finality":"SAFE","blockNumber":42,
                   "blockHash":"0xfrag","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(lifecycleProcessor).process(org.mockito.ArgumentMatchers.eq(chainId),
                any(), any(), any(), any());
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
        replacement.setCapabilities(Map.of("durabilityDomainId", "domain-a", "durableProtocolVersion", "2"));
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
        selected.setCapabilities(Map.of("durabilityDomainId", "domain-a", "durableProtocolVersion", "2"));
        RpcNode unrelated = node(true, 1, "2026-01-02T00:00:00Z");
        unrelated.setCapabilities(Map.of("durabilityDomainId", "domain-b", "durableProtocolVersion", "2"));
        when(chainConfigRepository.findByEnabledTrueAndFinalitySource(ChainConfig.FinalitySource.CHAINCACHE))
                .thenReturn(List.of(chain));
        when(rpcNodeRepository.findByChainConfig_IdAndKindAndEnabledTrue(
                chainId, RpcNode.NodeKind.CHAINCACHE)).thenReturn(List.of(selected, unrelated));
        activeSubscriptions().put(chainId, subscription);
        subscription.onOpen(webSocket);

        manager.reconcile();

        verify(webSocket).sendClose(WebSocket.NORMAL_CLOSURE, "no longer needed");
        verify(webSocket, times(1)).sendText(anyString(), anyBoolean());
        assertThat(activeSubscriptions()).doesNotContainKey(chainId);
        assertThat(manager.isConnected(chainId)).isFalse();
    }

    @Test
    @DisplayName("reconcile refuses a chain whose enabled candidates do not all support lifecycle v2")
    void reconcile_missingLifecycleV2Support_refusesStream() {
        ChainConfig chain = mock(ChainConfig.class);
        when(chain.getId()).thenReturn(chainId);
        RpcNode legacyOnly = node(true, 0, "2026-01-01T00:00:00Z");
        legacyOnly.setCapabilities(Map.of("durabilityDomainId", "domain-a"));
        when(chainConfigRepository.findByEnabledTrueAndFinalitySource(ChainConfig.FinalitySource.CHAINCACHE))
                .thenReturn(List.of(chain));
        when(rpcNodeRepository.findByChainConfig_IdAndKindAndEnabledTrue(
                chainId, RpcNode.NodeKind.CHAINCACHE)).thenReturn(List.of(legacyOnly));

        manager.reconcile();

        assertThat(activeSubscriptions()).doesNotContainKey(chainId);
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

    /** Opens the connection and completes the lifecycle subscribe handshake (request id 1). */
    private void subscribeSuccessfully() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-1","consumerId":"registerwerk-test-consumer",
                 "acknowledgedSequence":0,"startSequence":0,"schemaVersion":"2","durabilityDomainId":"domain-a",
                 "chainKey":"sepolia"}}
                """, true);
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
        return new ChaincacheDurableStreamManager(chainConfigRepository, rpcNodeRepository,
                lifecycleProcessor, lifecycleFailureRecorder,
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
}
