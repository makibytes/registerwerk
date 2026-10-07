package de.makibytes.registerwerk.stepup.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** {@code registerwerk.auth.step-up.approval-queue.*} (T8-02). */
@Component
@ConfigurationProperties(prefix = "registerwerk.auth.step-up.approval-queue")
public class ApprovalQueueProperties {

    /** How long a request may wait for a decision, and for the requester to claim an approval. */
    private Duration ttl = Duration.ofMinutes(15);
    /** Upper bound of the canonical request body copied into a request (an approver has to read it). */
    private int maxBodyBytes = 65_536;
    /** Open (PENDING or APPROVED) requests one requester may hold at a time. */
    private int maxOpenPerRequester = 20;
    /** How often the expiry sweep runs. */
    private Duration sweepInterval = Duration.ofMinutes(1);

    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
    public int getMaxOpenPerRequester() { return maxOpenPerRequester; }
    public void setMaxOpenPerRequester(int maxOpenPerRequester) { this.maxOpenPerRequester = maxOpenPerRequester; }
    public Duration getSweepInterval() { return sweepInterval; }
    public void setSweepInterval(Duration sweepInterval) { this.sweepInterval = sweepInterval; }
}
