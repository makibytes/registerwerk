package de.makibytes.registerwerk.wallet.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Cloud-KMS signer settings (T7-05). Bound on {@code registerwerk.wallet} so the selector reads
 * {@code registerwerk.wallet.signer=kms}; the nested {@code registerwerk.wallet.kms.*} block also holds
 * the KEK provider's {@code key-id} (read via {@code @Value} elsewhere, ignored here).
 *
 * <p>Blank or {@code software} keeps today's behaviour (software keystores and the PKCS#11 HSM).
 * Credentials are never configured here: the provider client uses Application Default Credentials /
 * workload identity.
 */
@ConfigurationProperties(prefix = "registerwerk.wallet")
public class KmsProperties {

    private static final Set<String> SIGNERS = Set.of("", "software", "kms");

    private String signer = "";
    private final Kms kms = new Kms();

    public String getSigner() { return signer; }

    public void setSigner(String signer) {
        String normalised = signer == null ? "" : signer.trim().toLowerCase(Locale.ROOT);
        if (!SIGNERS.contains(normalised)) {
            throw new IllegalArgumentException("registerwerk.wallet.signer must be blank, 'software' or 'kms', was '"
                    + signer + "'");
        }
        this.signer = normalised;
    }

    public Kms getKms() { return kms; }

    public boolean kmsSignerSelected() {
        return "kms".equals(signer);
    }

    public static class Kms {
        /** {@code gcp} today; {@code aws} can be added behind {@link KmsSigningClient}. */
        private String provider = "gcp";
        /** Provider key reference used by the startup health check (GCP: full cryptoKeyVersions resource name). */
        private String keyVersion = "";
        /** Per-attempt deadline of one KMS call. */
        private Duration timeout = Duration.ofSeconds(5);
        /** Total attempts per operation (1 = no retry). */
        private int maxAttempts = 3;
        /** First retry delay; doubles per attempt. */
        private Duration retryBackoff = Duration.ofMillis(200);
        /** Deadline of the startup reachability probe. */
        private Duration healthCheckTimeout = Duration.ofSeconds(10);

        public String getProvider() { return provider; }
        public void setProvider(String provider) {
            this.provider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        }
        public String getKeyVersion() { return keyVersion; }
        public void setKeyVersion(String keyVersion) { this.keyVersion = keyVersion == null ? "" : keyVersion.trim(); }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getRetryBackoff() { return retryBackoff; }
        public void setRetryBackoff(Duration retryBackoff) { this.retryBackoff = retryBackoff; }
        public Duration getHealthCheckTimeout() { return healthCheckTimeout; }
        public void setHealthCheckTimeout(Duration healthCheckTimeout) { this.healthCheckTimeout = healthCheckTimeout; }
    }
}
