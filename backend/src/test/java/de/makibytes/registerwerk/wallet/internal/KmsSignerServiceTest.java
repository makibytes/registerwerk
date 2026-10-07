package de.makibytes.registerwerk.wallet.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

import static de.makibytes.registerwerk.wallet.internal.KmsSigningException.Kind.PERMANENT;
import static de.makibytes.registerwerk.wallet.internal.KmsSigningException.Kind.TRANSIENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("KmsSignerService - public-key cache, bounded retries, timeouts, metrics")
class KmsSignerServiceTest {

    private FakeKmsSigningClient kms;
    private SimpleMeterRegistry registry;
    private KmsSignerService service;

    @BeforeEach
    void setUp() throws Exception {
        kms = new FakeKmsSigningClient(Keys.createEcKeyPair());
        registry = new SimpleMeterRegistry();
        service = service(kms, k -> { });
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private KmsSignerService service(KmsSigningClient client, Consumer<KmsProperties.Kms> tweak) {
        KmsProperties props = new KmsProperties();
        props.setSigner("kms");
        props.getKms().setKeyVersion(FakeKmsSigningClient.KEY);
        props.getKms().setRetryBackoff(Duration.ofMillis(1));
        props.getKms().setMaxAttempts(3);
        props.getKms().setTimeout(Duration.ofSeconds(2));
        tweak.accept(props.getKms());
        return new KmsSignerService(props, Optional.ofNullable(client), registry);
    }

    private static final byte[] DIGEST = new byte[32];

    @Test
    @DisplayName("the public key is fetched once per key reference and cached")
    void publicKeyCached() {
        service.signerFor(FakeKmsSigningClient.KEY, null);
        service.signerFor(FakeKmsSigningClient.KEY, null);
        service.sign(FakeKmsSigningClient.KEY, DIGEST);
        assertThat(kms.publicKeyCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("transient failures are retried within the bound and then succeed")
    void retriesTransientFailures() {
        kms.failures.add(new KmsSigningException(TRANSIENT, "unavailable"));
        kms.failures.add(new KmsSigningException(TRANSIENT, "unavailable"));
        assertThat(service.sign(FakeKmsSigningClient.KEY, DIGEST)).isNotEmpty();
        assertThat(kms.signCalls.get()).isEqualTo(3);
        assertThat(registry.get("registerwerk_kms_sign_retries_total").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("registerwerk_kms_sign_total").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("registerwerk_kms_sign_seconds").tag("outcome", "success").timer().count())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("retries are bounded: maxAttempts calls, then the last failure surfaces and is counted")
    void retriesAreBounded() {
        for (int i = 0; i < 10; i++) {
            kms.failures.add(new KmsSigningException(TRANSIENT, "unavailable"));
        }
        assertThatThrownBy(() -> service.sign(FakeKmsSigningClient.KEY, DIGEST))
                .isInstanceOf(KmsSigningException.class).hasMessageContaining("unavailable");
        assertThat(kms.signCalls.get()).isEqualTo(3);
        assertThat(registry.get("registerwerk_kms_sign_failures_total").tag("reason", "transient")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("registerwerk_kms_sign_total").tag("outcome", "failure").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("permanent failures (permission, missing key) are never retried")
    void permanentFailuresNotRetried() {
        kms.failures.add(new KmsSigningException(PERMANENT, "permission denied"));
        assertThatThrownBy(() -> service.sign(FakeKmsSigningClient.KEY, DIGEST))
                .isInstanceOf(KmsSigningException.class).hasMessageContaining("permission denied");
        assertThat(kms.signCalls.get()).isEqualTo(1);
        assertThat(registry.get("registerwerk_kms_sign_failures_total").tag("reason", "permanent")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a stalled KMS call is cut off at the timeout, retried, and finally fails as TIMEOUT")
    void timeoutIsEnforced() {
        try (KmsSignerService fast = service(kms, k -> {
            k.setTimeout(Duration.ofMillis(60));
            k.setMaxAttempts(2);
        })) {
            kms.delay = Duration.ofSeconds(5);
            long start = System.nanoTime();
            assertThatThrownBy(() -> fast.sign(FakeKmsSigningClient.KEY, DIGEST))
                    .isInstanceOfSatisfying(KmsSigningException.class,
                            e -> assertThat(e.kind()).isEqualTo(KmsSigningException.Kind.TIMEOUT));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertThat(kms.signCalls.get()).isEqualTo(2);
            assertThat(registry.get("registerwerk_kms_sign_failures_total").tag("reason", "timeout")
                    .counter().count()).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("a public key that is not a point on secp256k1 is rejected")
    void rejectsInvalidPublicKey() {
        byte[] bogus = new byte[65];
        bogus[0] = 0x04;
        bogus[64] = 1;
        kms.publicKeyOverride = bogus;
        assertThatThrownBy(() -> service.signerFor(FakeKmsSigningClient.KEY, null))
                .isInstanceOf(KmsSigningException.class).hasMessageContaining("secp256k1");
        kms.publicKeyOverride = new byte[33];
        assertThatThrownBy(() -> service.signerFor(FakeKmsSigningClient.KEY, null))
                .isInstanceOf(KmsSigningException.class).hasMessageContaining("uncompressed");
    }

    @Test
    @DisplayName("errors never contain the digest")
    void errorsDoNotLeakDigest() {
        byte[] digest = new byte[32];
        java.util.Arrays.fill(digest, (byte) 0xAB);
        kms.failures.add(new KmsSigningException(PERMANENT, "denied"));
        assertThatThrownBy(() -> service.sign(FakeKmsSigningClient.KEY, digest))
                .satisfies(e -> assertThat(String.valueOf(e.getMessage()))
                        .doesNotContain(Numeric.toHexStringNoPrefix(digest)));
    }

    @Test
    @DisplayName("probe derives the address within a deadline and reports failures")
    void probe() {
        assertThat(service.probe(FakeKmsSigningClient.KEY, Duration.ofSeconds(2)))
                .isEqualTo(Keys.toChecksumAddress(Credentials.create(kms.key).getAddress()));
        kms.publicKeyFailure = new KmsSigningException(TRANSIENT, "unreachable");
        assertThatThrownBy(() -> service.probe(FakeKmsSigningClient.KEY, Duration.ofSeconds(2)))
                .isInstanceOf(KmsSigningException.class).hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("disabled by default, unsupported provider reported, key references validated by the provider")
    void enablement() {
        KmsSignerService off = new KmsSignerService(new KmsProperties(), Optional.empty(), registry);
        assertThat(off.isEnabled()).isFalse();
        assertThatThrownBy(() -> off.signerFor(FakeKmsSigningClient.KEY, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("registerwerk.wallet.signer=kms");

        KmsSignerService noProvider = service(null, k -> k.setProvider("aws"));
        assertThat(noProvider.isEnabled()).isTrue();
        assertThat(noProvider.providerAvailable()).isFalse();
        assertThat(noProvider.provider()).isEqualTo("aws");
        assertThatThrownBy(() -> noProvider.sign(FakeKmsSigningClient.KEY, DIGEST))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("provider 'aws'");

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.configuredKeyReference()).isEqualTo(FakeKmsSigningClient.KEY);
        assertThat(service.isValidKeyReference(FakeKmsSigningClient.KEY)).isTrue();
        assertThat(service.isValidKeyReference("alias/x")).isFalse();
    }
}
