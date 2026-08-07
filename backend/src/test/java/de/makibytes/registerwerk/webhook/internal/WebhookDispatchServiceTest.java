package de.makibytes.registerwerk.webhook.internal;

import com.sun.net.httpserver.HttpServer;
import de.makibytes.registerwerk.webhook.api.WebhookDelivery;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryRepository;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryStatus;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import de.makibytes.registerwerk.webhook.api.WebhookSubscription;
import de.makibytes.registerwerk.webhook.api.WebhookSubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uses a real local {@link HttpServer} (JDK built-in, no new test dependency) rather than mocking
 * {@code RestClient} — the thing actually worth verifying here is that a real HTTP POST goes out
 * with the correct signature header and the response is correctly translated into delivery status.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookDispatchService unit tests (Track 6-1)")
class WebhookDispatchServiceTest {

    @Mock private WebhookSubscriptionRepository subscriptionRepository;
    @Mock private WebhookDeliveryRepository deliveryRepository;

    private WebhookDispatchService dispatchService;
    private final WebhookSigningService signingService = new WebhookSigningService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    private final AtomicReference<String> lastSignatureHeader = new AtomicReference<>();
    private final AtomicReference<Integer> requestCount = new AtomicReference<>(0);

    private final UUID entityId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            requestCount.updateAndGet(n -> n + 1);
            lastSignatureHeader.set(exchange.getRequestHeaders().getFirst("X-Registerwerk-Signature"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();

        dispatchService = new WebhookDispatchService(
                subscriptionRepository, deliveryRepository, signingService, objectMapper, RestClient.builder());
        lenient().when(deliveryRepository.save(any(WebhookDelivery.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private WebhookSubscription subscription(String path) {
        WebhookSubscription s = new WebhookSubscription();
        s.setEntityId(entityId);
        s.setUrl(baseUrl + path);
        s.setSecret("test-secret");
        s.setEnabled(true);
        return s;
    }

    @Test
    @DisplayName("dispatch sends a signed POST and marks the delivery SUCCESS on a 2xx response")
    void dispatch_success_marksDeliverySuccess() {
        responseStatus.set(200);
        WebhookSubscription sub = subscription("/hook");
        when(subscriptionRepository.findByEntityIdAndEnabledTrue(entityId)).thenReturn(List.of(sub));

        dispatchService.dispatch(entityId, WebhookEventType.TRADE_EXECUTED, Map.of("executionId", "abc"));

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(lastSignatureHeader.get()).isNotBlank();

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository, times(2)).save(captor.capture()); // once on create, once after attempt
        WebhookDelivery finalState = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(finalState.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(finalState.getResponseCode()).isEqualTo(200);
        assertThat(finalState.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("dispatch marks the delivery FAILED on a non-2xx response")
    void dispatch_serverError_marksDeliveryFailed() {
        responseStatus.set(500);
        WebhookSubscription sub = subscription("/hook");
        when(subscriptionRepository.findByEntityIdAndEnabledTrue(entityId)).thenReturn(List.of(sub));

        dispatchService.dispatch(entityId, WebhookEventType.TRADE_EXECUTED, Map.of("executionId", "abc"));

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository, times(2)).save(captor.capture());
        WebhookDelivery finalState = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(finalState.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
    }

    @Test
    @DisplayName("dispatch marks the delivery FAILED when the endpoint is unreachable")
    void dispatch_unreachableEndpoint_marksDeliveryFailed() {
        WebhookSubscription sub = subscription("/hook");
        sub.setUrl("http://localhost:1"); // nothing listens on port 1
        when(subscriptionRepository.findByEntityIdAndEnabledTrue(entityId)).thenReturn(List.of(sub));

        dispatchService.dispatch(entityId, WebhookEventType.TRADE_EXECUTED, Map.of("executionId", "abc"));

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
    }

    @Test
    @DisplayName("dispatch skips subscriptions not subscribed to the event type")
    void dispatch_skipsUnsubscribedEventType() {
        WebhookSubscription sub = subscription("/hook");
        sub.setEventTypes(java.util.Set.of(WebhookEventType.KYC_APPROVED)); // not TRADE_EXECUTED
        when(subscriptionRepository.findByEntityIdAndEnabledTrue(entityId)).thenReturn(List.of(sub));

        dispatchService.dispatch(entityId, WebhookEventType.TRADE_EXECUTED, Map.of("executionId", "abc"));

        assertThat(requestCount.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("dispatch is a no-op for a null entityId")
    void dispatch_nullEntityId_isNoOp() {
        dispatchService.dispatch(null, WebhookEventType.TRADE_EXECUTED, Map.of());

        assertThat(requestCount.get()).isEqualTo(0);
    }
}
