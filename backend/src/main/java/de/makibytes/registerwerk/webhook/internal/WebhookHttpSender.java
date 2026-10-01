package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.webhook.api.WebhookDeliveryOutcome;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;

/**
 * The only place that opens outbound connections for webhooks. Apache HttpClient 5 with:
 * <ul>
 *   <li>a {@link DnsResolver} that resolves through {@link OutboundUrlPolicy#resolveValidated} — the
 *       connection goes to the validated addresses only (pinning; Host header and TLS SNI/hostname
 *       verification still use the original name), so DNS rebinding after the policy check is moot;</li>
 *   <li>redirects disabled (a 3xx is just a failed delivery, never followed to an internal address),
 *       automatic retries disabled (retrying is the sweep's job), no proxy;</li>
 *   <li>connect and response timeouts (defaults 3 s / 5 s); the response body is never read.</li>
 * </ul>
 */
@Component
class WebhookHttpSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookHttpSender.class);

    /** Result of one send: the coarse outcome plus the HTTP status (internal only; null if no response). */
    record SendResult(WebhookDeliveryOutcome outcome, Integer httpStatus) {}

    private final OutboundUrlPolicy policy;
    private final CloseableHttpClient client;

    WebhookHttpSender(OutboundUrlPolicy policy, WebhookProperties properties) {
        this.policy = policy;
        DnsResolver pinning = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return WebhookHttpSender.this.policy.resolveValidated(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinning)
                .setMaxConnTotal(64)
                .setMaxConnPerRoute(4)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(properties.getConnectTimeoutMs()))
                        .setSocketTimeout(Timeout.ofMilliseconds(properties.getResponseTimeoutMs()))
                        .build())
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(properties.getResponseTimeoutMs()))
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(properties.getConnectTimeoutMs()))
                        .setRedirectsEnabled(false)
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .build();
    }

    SendResult post(String url, String body, Map<String, String> headers) {
        if (policy.check(url) == OutboundUrlPolicy.Verdict.BLOCKED) {
            return new SendResult(WebhookDeliveryOutcome.BLOCKED, null);
        }
        HttpPost request = new HttpPost(URI.create(url.trim()));
        headers.forEach(request::setHeader);
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        try (ClassicHttpResponse response = client.executeOpen(null, request, null)) {
            int status = response.getCode();
            // Closing without consuming: the body is ignored and the connection is not reused.
            return new SendResult(status >= 200 && status < 300
                    ? WebhookDeliveryOutcome.OK : WebhookDeliveryOutcome.RECEIVER_ERROR, status);
        } catch (OutboundUrlPolicy.BlockedDestinationException e) {
            return new SendResult(WebhookDeliveryOutcome.BLOCKED, null);
        } catch (Exception e) {
            if (isBlocked(e)) {
                return new SendResult(WebhookDeliveryOutcome.BLOCKED, null);
            }
            log.debug("Webhook send failed: {}", e.getClass().getSimpleName());
            return new SendResult(WebhookDeliveryOutcome.UNREACHABLE, null);
        }
    }

    private static boolean isBlocked(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof OutboundUrlPolicy.BlockedDestinationException) return true;
        }
        return false;
    }

    @PreDestroy
    void close() {
        try {
            client.close(org.apache.hc.core5.io.CloseMode.GRACEFUL);
        } catch (Exception ignored) {
            // shutting down
        }
    }
}
