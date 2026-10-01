package de.makibytes.registerwerk.webhook.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "registerwerk.webhook")
public class WebhookProperties {

    /** Dev/local receivers only: permits http, arbitrary ports and loopback/private targets (never
     *  the cloud-metadata / link-local ranges). Refused at start-up when REGISTERWERK_PRODUCTION_MODE=true. */
    private boolean allowInsecureUrls = false;
    /** Destination ports accepted when {@code allowInsecureUrls} is false. */
    private List<Integer> allowedPorts = List.of(443, 8443);
    private int connectTimeoutMs = 3000;
    private int responseTimeoutMs = 5000;
    /** Worker threads for the retry sweep; a slow endpoint only ever occupies one of them. */
    private int maxConcurrency = 8;
    private int maxPerHost = 2;
    private int sweepBatchSize = 100;
    /** Lease applied to a claimed delivery so a crashed node's rows come back after this. */
    private int leaseSeconds = 120;
    private int maxAttempts = 8;
    private int circuitBreakerFailures = 20;
    private int rotationOverlapHours = 24;
    /** Signature tolerance documented to receivers (they reject timestamps older than this). */
    private int replayToleranceSeconds = 300;

    public boolean isAllowInsecureUrls() { return allowInsecureUrls; }
    public void setAllowInsecureUrls(boolean v) { this.allowInsecureUrls = v; }
    public List<Integer> getAllowedPorts() { return allowedPorts; }
    public void setAllowedPorts(List<Integer> v) { this.allowedPorts = v; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
    public int getResponseTimeoutMs() { return responseTimeoutMs; }
    public void setResponseTimeoutMs(int v) { this.responseTimeoutMs = v; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int v) { this.maxConcurrency = v; }
    public int getMaxPerHost() { return maxPerHost; }
    public void setMaxPerHost(int v) { this.maxPerHost = v; }
    public int getSweepBatchSize() { return sweepBatchSize; }
    public void setSweepBatchSize(int v) { this.sweepBatchSize = v; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int v) { this.leaseSeconds = v; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }
    public int getCircuitBreakerFailures() { return circuitBreakerFailures; }
    public void setCircuitBreakerFailures(int v) { this.circuitBreakerFailures = v; }
    public int getRotationOverlapHours() { return rotationOverlapHours; }
    public void setRotationOverlapHours(int v) { this.rotationOverlapHours = v; }
    public int getReplayToleranceSeconds() { return replayToleranceSeconds; }
    public void setReplayToleranceSeconds(int v) { this.replayToleranceSeconds = v; }
}
