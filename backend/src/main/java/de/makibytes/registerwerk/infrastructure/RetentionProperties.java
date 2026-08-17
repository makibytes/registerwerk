package de.makibytes.registerwerk.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-target configuration for {@link RetentionSweepJob} — {@code registerwerk.retention.*}.
 *
 * <p>Every target is independently enabled/disabled and independently configured, e.g.:
 * <pre>
 * registerwerk:
 *   retention:
 *     login-attempt:
 *       enabled: true
 *       max-age: 30d
 *       batch-size: 500
 * </pre>
 *
 * <p>Only technical/security-artifact tables are swept here (spent nonces, expired tokens,
 * brute-force counters, terminal webhook deliveries, completed Modulith outbox rows). Tables
 * that could carry legal or compliance evidentiary weight (screening decisions, corporate-action
 * tax records, chain-drift incident records) are deliberately NOT swept by this job — see the
 * code comments in {@link RetentionSweepJob} for the reasoning per table, and
 * {@code kyc.api.JurisdictionRequirementConfig#dataRetentionPeriod} for the legal-hold durations
 * (10 years for DE/LI) that make automated deletion of anything evidentiary too risky to
 * automate in Phase 1.
 */
@Component
@ConfigurationProperties(prefix = "registerwerk.retention")
public class RetentionProperties {

    private final Target loginAttempt = new Target(true, Duration.ofDays(30), 500);
    private final Target walletBindChallenge = new Target(true, Duration.ofDays(30), 500);
    private final Target onboardingToken = new Target(true, Duration.ofDays(90), 500);
    private final Target appUserActionToken = new Target(true, Duration.ofDays(90), 500);
    private final Target webhookDelivery = new Target(true, Duration.ofDays(90), 500);
    private final Target eventPublication = new Target(true, Duration.ofDays(30), 500);

    public Target getLoginAttempt() { return loginAttempt; }
    public Target getWalletBindChallenge() { return walletBindChallenge; }
    public Target getOnboardingToken() { return onboardingToken; }
    public Target getAppUserActionToken() { return appUserActionToken; }
    public Target getWebhookDelivery() { return webhookDelivery; }
    public Target getEventPublication() { return eventPublication; }

    /** One sweep target's toggle + window + batch size. */
    public static class Target {

        private boolean enabled;
        private Duration maxAge;
        private int batchSize;

        public Target() {
        }

        Target(boolean enabled, Duration maxAge, int batchSize) {
            this.enabled = enabled;
            this.maxAge = maxAge;
            this.batchSize = batchSize;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Duration getMaxAge() { return maxAge; }
        public void setMaxAge(Duration maxAge) { this.maxAge = maxAge; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }
}
