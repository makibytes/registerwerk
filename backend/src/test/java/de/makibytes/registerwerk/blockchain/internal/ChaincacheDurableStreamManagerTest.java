package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
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
        manager = new ChaincacheDurableStreamManager(chainConfigRepository, rpcNodeRepository, blockFinalityFeed, objectMapper);
        subscription = manager.new ChainSubscription(chainId, "registerwerk-" + chainId);
    }

    @Test
    @DisplayName("onOpen subscribes to both SAFE and FINALIZED streams")
    void onOpen_subscribesToBothStreams() {
        subscription.onOpen(webSocket);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(2)).sendText(sent.capture(), anyBoolean());
        List<String> messages = sent.getAllValues();
        assertThat(messages).anyMatch(m -> m.contains("\"stream\":\"SAFE\""));
        assertThat(messages).anyMatch(m -> m.contains("\"stream\":\"FINALIZED\""));
        assertThat(messages).allMatch(m -> m.contains("chaincache_subscribeDurable"));
    }

    @Test
    @DisplayName("a BLOCK event on the SAFE subscription records a SAFE observation and acknowledges it")
    void blockEvent_onSafeSubscription_recordsObservationAndAcks() {
        subscription.onOpen(webSocket);

        // Subscribe result for request id=1 (SAFE, sent first) — chaincache assigns local subscription id "sub-1".
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-1","consumerId":"c","stream":"SAFE","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-1","result":
                  {"sequence":5,"eventId":"e1","stream":"SAFE","kind":"BLOCK","blockNumber":100,
                   "blockHash":"0xabc","payload":{},"retractsEventId":null,"createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed).recordObservation(chainId, 100L, "0xabc", FinalityLevel.SAFE);
        verify(blockFinalityFeed, never()).recordRetraction(any(), anyLong(), any(), anyInt());

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(webSocket, times(3)).sendText(sent.capture(), anyBoolean());
        assertThat(sent.getAllValues()).anyMatch(m -> m.contains("chaincache_ack") && m.contains("\"sequence\":5"));
    }

    @Test
    @DisplayName("a RETRACTION event records a retraction, not an observation")
    void retractionEvent_recordsRetraction() {
        subscription.onOpen(webSocket);
        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","id":2,"result":{"subscription":"sub-2","consumerId":"c","stream":"FINALIZED","acknowledgedSequence":0}}
                """, true);

        subscription.onText(webSocket, """
                {"jsonrpc":"2.0","method":"chaincache_event","params":{"subscription":"sub-2","result":
                  {"sequence":9,"eventId":"e2","stream":"FINALIZED","kind":"RETRACTION","blockNumber":100,
                   "blockHash":"0xdef","payload":{},"retractsEventId":"e1","createdAt":"2026-01-01T00:00:00Z"}}}
                """, true);

        verify(blockFinalityFeed).recordRetraction(chainId, 100L, "0xdef", 0);
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
                {"jsonrpc":"2.0","id":1,"result":{"subscription":"sub-1","consumerId":"c","stream":"SAFE\
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
    @DisplayName("isOpen is false before onOpen and after onClose")
    void isOpen_reflectsLifecycle() {
        assertThat(subscription.isOpen()).isFalse();

        subscription.onOpen(webSocket);
        assertThat(subscription.isOpen()).isTrue();

        subscription.onClose(webSocket, 1000, "bye");
        assertThat(subscription.isOpen()).isFalse();
    }
}
