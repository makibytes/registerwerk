package de.makibytes.registerwerk.wallet.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Keys;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("KmsSignerReadinessCheck - ERROR in production, WARN otherwise")
class KmsSignerReadinessCheckTest {

    private FakeKmsSigningClient kms;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        kms = new FakeKmsSigningClient(Keys.createEcKeyPair());
        registry = new SimpleMeterRegistry();
    }

    private KmsSignerReadinessCheck check(String signer, String keyVersion, String provider, KmsSigningClient client) {
        KmsProperties props = new KmsProperties();
        props.setSigner(signer);
        props.getKms().setKeyVersion(keyVersion);
        props.getKms().setProvider(provider);
        props.getKms().setMaxAttempts(1);
        props.getKms().setHealthCheckTimeout(Duration.ofMillis(300));
        KmsSignerService service = new KmsSignerService(props, Optional.ofNullable(client), registry);
        return new KmsSignerReadinessCheck(service, props, registry);
    }

    private double gauge() {
        return registry.get("registerwerk_wallet_kms_signer_ready").gauge().value();
    }

    @Test
    @DisplayName("software signer: nothing is checked, gauge -1")
    void disabledIsNoOp() {
        KmsSignerReadinessCheck check = check("", "", "gcp", null);
        assertThatCode(() -> check.check(true)).doesNotThrowAnyException();
        assertThat(gauge()).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("reachable KMS key: ready in both modes")
    void ready() {
        KmsSignerReadinessCheck check = check("kms", FakeKmsSigningClient.KEY, "fake", kms);
        assertThatCode(() -> check.check(true)).doesNotThrowAnyException();
        assertThat(gauge()).isEqualTo(1.0);
        assertThat(kms.publicKeyCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("missing key version: production refuses, non-production warns")
    void missingKeyVersion() {
        KmsSignerReadinessCheck check = check("kms", "", "fake", kms);
        assertThatThrownBy(() -> check.check(true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registerwerk.wallet.kms.key-version");
        assertThat(gauge()).isEqualTo(0.0);
        assertThatCode(() -> check.check(false)).doesNotThrowAnyException();
        assertThat(gauge()).isEqualTo(0.0);
        assertThat(kms.publicKeyCalls.get()).isZero();
    }

    @Test
    @DisplayName("malformed key reference for the provider: production refuses")
    void malformedKeyVersion() {
        KmsSignerReadinessCheck check = check("kms", "alias/not-a-key-version", "fake", kms);
        assertThatThrownBy(() -> check.check(true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a valid");
        assertThatCode(() -> check.check(false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unsupported provider: production refuses")
    void unsupportedProvider() {
        KmsSignerReadinessCheck check = check("kms", FakeKmsSigningClient.KEY, "aws", null);
        assertThatThrownBy(() -> check.check(true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider 'aws'");
        assertThatCode(() -> check.check(false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unreachable KMS (startup probe times out or errors): production refuses, non-production warns")
    void unreachable() {
        kms.publicKeyFailure = new KmsSigningException(KmsSigningException.Kind.TRANSIENT, "unavailable");
        KmsSignerReadinessCheck check = check("kms", FakeKmsSigningClient.KEY, "fake", kms);
        assertThatThrownBy(() -> check.check(true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
        assertThat(gauge()).isEqualTo(0.0);
        assertThatCode(() -> check.check(false)).doesNotThrowAnyException();
    }
}
