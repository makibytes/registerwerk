package de.makibytes.registerwerk.webhook.internal;

import com.sun.net.httpserver.HttpServer;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real sockets against a JDK {@link HttpServer}: what matters is what actually goes on the wire. */
@DisplayName("WebhookHttpSender — pinning, redirects, timeouts (5D-08)")
class WebhookHttpSenderTest {

    private HttpServer server;
    private HttpServer secondServer;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger secondHits = new AtomicInteger();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> redirectTo = new AtomicReference<>();
    private final AtomicReference<Long> sleepMs = new AtomicReference<>(0L);
    private final AtomicReference<String> seenSignature = new AtomicReference<>();

    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            hits.incrementAndGet();
            seenSignature.set(ex.getRequestHeaders().getFirst("X-Registerwerk-Signature"));
            ex.getRequestBody().readAllBytes();
            try {
                Thread.sleep(sleepMs.get());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (redirectTo.get() != null) {
                ex.getResponseHeaders().add("Location", redirectTo.get());
                ex.sendResponseHeaders(302, -1);
            } else {
                ex.sendResponseHeaders(status.get(), -1);
            }
            ex.close();
        });
        server.start();
        secondServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secondServer.createContext("/", ex -> {
            secondHits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        secondServer.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        secondServer.stop(0);
    }

    private WebhookHttpSender lenientSender(int responseTimeoutMs) {
        WebhookProperties p = new WebhookProperties();
        p.setAllowInsecureUrls(true);
        p.setResponseTimeoutMs(responseTimeoutMs);
        p.setConnectTimeoutMs(1000);
        return new WebhookHttpSender(new OutboundUrlPolicy(p, false, host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }), p);
    }

    @Test
    @DisplayName("2xx is OK, 5xx is RECEIVER_ERROR; the signature header goes out")
    void okAndReceiverError() {
        WebhookHttpSender sender = lenientSender(2000);
        var ok = sender.post(base + "/hook", "{}", Map.of("X-Registerwerk-Signature", "v1=abc"));
        assertThat(ok.outcome()).isEqualTo(WebhookDeliveryOutcome.OK);
        assertThat(seenSignature.get()).isEqualTo("v1=abc");

        status.set(500);
        assertThat(sender.post(base + "/hook", "{}", Map.of()).outcome()).isEqualTo(WebhookDeliveryOutcome.RECEIVER_ERROR);
    }

    @Test
    @DisplayName("a redirect to another (even loopback) address is never followed")
    void redirectNotFollowed() {
        redirectTo.set("http://127.0.0.1:" + secondServer.getAddress().getPort() + "/internal");
        var result = lenientSender(2000).post(base + "/hook", "{}", Map.of());

        assertThat(result.outcome()).isEqualTo(WebhookDeliveryOutcome.RECEIVER_ERROR);
        assertThat(hits.get()).isEqualTo(1);
        assertThat(secondHits.get()).isZero();
    }

    @Test
    @DisplayName("a receiver that hangs is cut off at the response timeout")
    void timeout() {
        sleepMs.set(3000L);
        long t0 = System.nanoTime();
        var result = lenientSender(300).post(base + "/hook", "{}", Map.of());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(result.outcome()).isEqualTo(WebhookDeliveryOutcome.UNREACHABLE);
        assertThat(elapsedMs).isLessThan(2500);
    }

    @Test
    @DisplayName("connection refused is UNREACHABLE and carries no detail")
    void refused() throws Exception {
        int freePort;
        try (var s = new java.net.ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        var result = lenientSender(500).post("http://127.0.0.1:" + freePort + "/x", "{}", Map.of());
        assertThat(result.outcome()).isEqualTo(WebhookDeliveryOutcome.UNREACHABLE);
        assertThat(result.httpStatus()).isNull();
    }

    @Test
    @DisplayName("strict policy: a name resolving to a private address is BLOCKED and the server is never contacted")
    void privateTargetBlocked() {
        WebhookProperties p = new WebhookProperties();
        WebhookHttpSender sender = new WebhookHttpSender(new OutboundUrlPolicy(p, false, host -> literal("127.0.0.1")), p);
        var result = sender.post("https://hook.example.com:" + server.getAddress().getPort() + "/hook", "{}", Map.of());
        assertThat(result.outcome()).isEqualTo(WebhookDeliveryOutcome.BLOCKED);
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("DNS rebinding: public at the pre-check, loopback at connect time -> BLOCKED, listener never hit")
    void rebindingBlockedAtConnect() {
        WebhookProperties p = new WebhookProperties();
        p.setAllowedPorts(java.util.List.of(server.getAddress().getPort()));
        AtomicInteger lookups = new AtomicInteger();
        var policy = new OutboundUrlPolicy(p, false, host -> lookups.getAndIncrement() == 0
                ? literal("93.184.216.34") : literal("127.0.0.1"));
        WebhookHttpSender sender = new WebhookHttpSender(policy, p);

        var result = sender.post("https://rebind.example.com:" + server.getAddress().getPort() + "/hook", "{}", Map.of());

        assertThat(result.outcome()).isEqualTo(WebhookDeliveryOutcome.BLOCKED);
        assertThat(lookups.get()).isGreaterThanOrEqualTo(2);
        assertThat(hits.get()).isZero();
    }

    private static InetAddress[] literal(String ip) {
        try {
            return new InetAddress[]{InetAddress.getByName(ip)};
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
