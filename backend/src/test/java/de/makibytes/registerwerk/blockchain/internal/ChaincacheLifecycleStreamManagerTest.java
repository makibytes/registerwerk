package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.http.WebSocket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChaincacheLifecycleStreamManagerTest {

    @Mock ChainConfigRepository chains;
    @Mock RpcNodeRepository nodes;
    @Mock ChaincacheLifecycleEventProcessor processor;
    @Mock ChaincacheLifecycleFailureRecorder failureRecorder;
    @Mock WebSocket socket;

    private final ObjectMapper mapper = new ObjectMapper();
    private ChaincacheDurableStreamManager manager;
    private ChaincacheDurableStreamManager.ChainSubscription subscription;
    private UUID chainId;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(socket.sendText(anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(socket));
        org.mockito.Mockito.lenient().when(processor.earliestDeploymentBlock(any())).thenReturn(42L);
        manager = new ChaincacheDurableStreamManager(chains, nodes,
                processor, failureRecorder, mapper, managementUrl -> Optional.empty(), "stable", 5_000,
                60_000, 60_000, new SimpleMeterRegistry());
        manager.start();
        chainId = UUID.randomUUID();
        subscription = manager.new ChainSubscription(
                chainId, "registerwerk:stable:sepolia", "sepolia", UUID.randomUUID(), "domain-a");
    }

    @Test
    void subscribesToOneLifecycleStreamFromEarliestDeployment() {
        subscription.onOpen(socket);

        var sent = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(socket).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getValue()).contains("chaincache_subscribeLifecycle")
                .contains("\"consumerId\":\"registerwerk:stable:sepolia\"")
                .contains("\"startBlock\":42");
    }

    @Test
    void commitsProcessorBeforeAcknowledgingTheSingleCursor() {
        establish();
        clearInvocations(socket, processor);
        String event = lifecycleEvent(7, "log:0xabc:0x0");

        subscription.onText(socket, event, true);

        InOrder order = inOrder(processor, socket);
        order.verify(processor).process(any(), anyString(), anyString(), anyString(), any());
        order.verify(socket).sendText(org.mockito.ArgumentMatchers.contains("chaincache_ackLifecycle"),
                anyBoolean());
    }

    @Test
    void poisonEventIsJournalledAndNeverAcknowledged() {
        establish();
        clearInvocations(socket, processor, failureRecorder);
        RuntimeException poison = new IllegalArgumentException("bad log");
        org.mockito.Mockito.doThrow(poison).when(processor)
                .process(any(), anyString(), anyString(), anyString(), any());

        subscription.onText(socket, lifecycleEvent(7, "bad"), true);

        verify(failureRecorder).record(any(), anyString(), anyString(), anyString(), any(),
                org.mockito.ArgumentMatchers.same(poison));
        verify(socket, never()).sendText(org.mockito.ArgumentMatchers.contains("chaincache_ackLifecycle"),
                anyBoolean());
        verify(socket).sendClose(1011, "durable event processing failed");
    }

    @Test
    void logicalOccurrenceIdentityDropsPromotionAndReinstatementSuffixes() {
        String reinstated = "log:block:0x1:r6ea1644e-0e6d-4d3a-a6ce-6745fb6500f8:provisional:safe";
        assertThat(ChaincacheLifecycleEventProcessor.logicalEventId(reinstated))
                .isEqualTo("log:block:0x1");
        assertThat(ChaincacheLifecycleEventProcessor.canonicalTenure(reinstated))
                .isEqualTo("6ea1644e-0e6d-4d3a-a6ce-6745fb6500f8");
        assertThat(ChaincacheLifecycleEventProcessor.logicalEventId("log:block:0x1:provisional:finalized"))
                .isEqualTo("log:block:0x1");
    }

    private void establish() {
        subscription.onOpen(socket);
        subscription.onText(socket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-1",
                 "consumerId":"registerwerk:stable:sepolia","acknowledgedSequence":0,
                 "schemaVersion":"2","durabilityDomainId":"domain-a","chainKey":"sepolia"}}
                """, true);
    }

    private static String lifecycleEvent(long sequence, String eventId) {
        return """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"schemaVersion":"2","durabilityDomainId":"domain-a","chainKey":"sepolia",
                   "sequence":%d,"eventId":"%s","kind":"LOG","finality":"PROVISIONAL",
                   "blockNumber":100,"blockHash":"0xabc","payload":{},"retractsEventId":null,
                   "createdAt":"2026-01-01T00:00:00Z","reorg":null}}}
                """.formatted(sequence, eventId);
    }
}
