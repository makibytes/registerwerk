package de.makibytes.registerwerk.wallet.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Keys;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Cloud-KMS signing facade (T7-05), the KMS counterpart of {@link Pkcs11HsmService}: a single bean that
 * owns the provider client, the public-key cache and the call discipline. Every KMS call runs with a
 * hard per-attempt deadline and a bounded retry (only for TIMEOUT/TRANSIENT failures; signing is
 * side-effect free) and is metered; nothing here logs digests, signatures or key material.
 *
 * <p>Stateless apart from the public-key cache, so any number of replicas can sign with the same key.
 */
public class KmsSignerService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KmsSignerService.class);
    private static final String SUPPORTED_PROVIDERS = "gcp";

    private final KmsProperties properties;
    private final KmsSigningClient client;
    private final MeterRegistry registry;
    private final ExecutorService executor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("kms-call-", 0).factory());
    private final ConcurrentHashMap<String, byte[]> publicKeys = new ConcurrentHashMap<>();
    private final Counter signSuccess;
    private final Counter signFailure;
    private final Counter signRetries;
    private final Timer successTimer;
    private final Timer failureTimer;

    public KmsSignerService(KmsProperties properties, Optional<KmsSigningClient> client, MeterRegistry registry) {
        this.properties = properties;
        this.client = client.orElse(null);
        this.registry = registry;
        String provider = properties.getKms().getProvider();
        this.signSuccess = registry.counter("registerwerk_kms_sign_total", "provider", provider, "outcome", "success");
        this.signFailure = registry.counter("registerwerk_kms_sign_total", "provider", provider, "outcome", "failure");
        this.signRetries = registry.counter("registerwerk_kms_sign_retries_total", "provider", provider);
        this.successTimer = Timer.builder("registerwerk_kms_sign_seconds")
                .description("Latency of one KMS signing operation including retries")
                .tags("provider", provider, "outcome", "success").register(registry);
        this.failureTimer = Timer.builder("registerwerk_kms_sign_seconds")
                .tags("provider", provider, "outcome", "failure").register(registry);
    }

    /** True when {@code registerwerk.wallet.signer=kms}. */
    public boolean isEnabled() {
        return properties.kmsSignerSelected();
    }

    /** False when the configured provider has no adapter (e.g. a typo, or aws before it exists). */
    public boolean providerAvailable() {
        return client != null;
    }

    public String provider() {
        return properties.getKms().getProvider();
    }

    /** The key reference the startup health check probes ({@code registerwerk.wallet.kms.key-version}). */
    public String configuredKeyReference() {
        return properties.getKms().getKeyVersion();
    }

    public Duration healthCheckTimeout() {
        return properties.getKms().getHealthCheckTimeout();
    }

    public boolean isValidKeyReference(String keyReference) {
        return client != null && client.isValidKeyReference(keyReference);
    }

    /** Opaque signer for a KMS key; resolves (and caches) the public key, checking {@code expectedAddress}. */
    public KmsEvmSigner signerFor(String keyReference, String expectedAddress) {
        return KmsEvmSigner.create(this, keyReference, expectedAddress);
    }

    /** Validated uncompressed public point of {@code keyReference}; fetched once, then cached. */
    byte[] publicKey(String keyReference) {
        KmsSigningClient c = requireClient();
        byte[] cached = publicKeys.get(keyReference);
        if (cached != null) {
            return cached;
        }
        KmsProperties.Kms cfg = properties.getKms();
        byte[] point = invoke(() -> c.getPublicKey(keyReference), cfg.getTimeout(), attempts(), null);
        validatePoint(point);
        publicKeys.put(keyReference, point);
        return point;
    }

    /** DER ECDSA signature of {@code digest} (32 bytes) as returned by the KMS; bounded retries, metered. */
    byte[] sign(String keyReference, byte[] digest) {
        KmsSigningClient c = requireClient();
        Timer.Sample sample = Timer.start(registry);
        try {
            byte[] der = invoke(() -> c.signDigest(keyReference, digest),
                    properties.getKms().getTimeout(), attempts(), signRetries);
            signSuccess.increment();
            sample.stop(successTimer);
            return der;
        } catch (KmsSigningException e) {
            signFailure.increment();
            recordSignatureRejected(e.kind().name().toLowerCase(java.util.Locale.ROOT));
            sample.stop(failureTimer);
            log.warn("KMS signing failed ({}): {}", e.kind(), e.getMessage());
            throw e;
        }
    }

    /** Counts a signature the KMS returned but that could not be used (invalid, or wrong key). */
    void recordSignatureRejected(String reason) {
        registry.counter("registerwerk_kms_sign_failures_total", "provider", provider(), "reason", reason).increment();
    }

    /**
     * Startup/readiness probe: fetches the public key once within {@code timeout} (single attempt, not
     * cached) and returns the derived Ethereum address.
     */
    public String probe(String keyReference, Duration timeout) {
        KmsSigningClient c = requireClient();
        byte[] point = invoke(() -> c.getPublicKey(keyReference), timeout, 1, null);
        validatePoint(point);
        return addressOf(point);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static String addressOf(byte[] uncompressedPoint) {
        BigInteger xy = new BigInteger(1, Arrays.copyOfRange(uncompressedPoint, 1, 65));
        return Keys.toChecksumAddress("0x" + Keys.getAddress(xy));
    }

    static void validatePoint(byte[] point) {
        if (point == null || point.length != 65 || point[0] != 0x04) {
            throw new KmsSigningException(KmsSigningException.Kind.PERMANENT,
                    "KMS public key is not an uncompressed secp256k1 point (expected 65 bytes starting with 0x04)");
        }
        try {
            ECPoint decoded = CustomNamedCurves.getByName("secp256k1").getCurve().decodePoint(point);
            if (decoded.isInfinity() || !decoded.isValid()) {
                throw new IllegalArgumentException("invalid point");
            }
        } catch (IllegalArgumentException e) {
            throw new KmsSigningException(KmsSigningException.Kind.PERMANENT,
                    "KMS public key is not a valid point on secp256k1");
        }
    }

    private KmsSigningClient requireClient() {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "KMS signer support is not enabled for this instance (registerwerk.wallet.signer=kms)");
        }
        if (client == null) {
            throw new IllegalStateException("No KMS signing client for provider '" + provider()
                    + "' (supported: " + SUPPORTED_PROVIDERS + ")");
        }
        return client;
    }

    private int attempts() {
        return Math.max(1, properties.getKms().getMaxAttempts());
    }

    private <T> T invoke(Supplier<T> call, Duration timeout, int maxAttempts, Counter retries) {
        KmsSigningException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return callOnce(call, timeout);
            } catch (KmsSigningException e) {
                last = e;
                if (!e.retryable() || attempt == maxAttempts) {
                    break;
                }
                if (retries != null) {
                    retries.increment();
                }
                backoff(attempt);
            }
        }
        throw last;
    }

    private <T> T callOnce(Supplier<T> call, Duration timeout) {
        Future<T> future = executor.submit(call::get);
        try {
            return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new KmsSigningException(KmsSigningException.Kind.TIMEOUT,
                    "KMS call timed out after " + timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new KmsSigningException(KmsSigningException.Kind.PERMANENT, "interrupted while waiting for KMS");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof KmsSigningException kms) {
                throw kms;
            }
            throw new KmsSigningException(KmsSigningException.Kind.PERMANENT,
                    "KMS call failed: " + cause.getClass().getSimpleName(), cause);
        }
    }

    private void backoff(int attempt) {
        long millis = properties.getKms().getRetryBackoff().toMillis() << Math.min(attempt - 1, 6);
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(millis, 5_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
