package de.makibytes.registerwerk.wallet.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KMS signer selection by property (registerwerk.wallet.signer / kms.provider)")
class KmsSignerConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KmsSignerConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    @DisplayName("default (blank signer): today's behaviour, no KMS client, service disabled")
    void defaultIsUnchanged() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean(KmsSigningClient.class);
            KmsSignerService service = ctx.getBean(KmsSignerService.class);
            assertThat(service.isEnabled()).isFalse();
        });
        runner.withPropertyValues("registerwerk.wallet.signer=software", "registerwerk.wallet.kms.provider=gcp")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(KmsSigningClient.class);
                    assertThat(ctx.getBean(KmsSignerService.class).isEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("signer=kms selects the GCP adapter by default and binds the tuning properties; no credentials are touched")
    void kmsSelectsGcp() {
        runner.withPropertyValues(
                        "registerwerk.wallet.signer=KMS",
                        "registerwerk.wallet.kms.key-version=projects/p/locations/l/keyRings/r/cryptoKeys/k/cryptoKeyVersions/1",
                        "registerwerk.wallet.kms.timeout=PT2S",
                        "registerwerk.wallet.kms.max-attempts=5")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(KmsSigningClient.class);
                    assertThat(ctx.getBean(KmsSigningClient.class)).isInstanceOf(GcpKmsSigningClient.class);
                    KmsSignerService service = ctx.getBean(KmsSignerService.class);
                    assertThat(service.isEnabled()).isTrue();
                    assertThat(service.providerAvailable()).isTrue();
                    assertThat(service.provider()).isEqualTo("gcp");
                    assertThat(service.configuredKeyReference()).endsWith("cryptoKeyVersions/1");
                    KmsProperties props = ctx.getBean(KmsProperties.class);
                    assertThat(props.getKms().getTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(props.getKms().getMaxAttempts()).isEqualTo(5);
                });
    }

    @Test
    @DisplayName("an unsupported provider yields no client (reported by the readiness check), never a silent fallback")
    void unsupportedProvider() {
        runner.withPropertyValues("registerwerk.wallet.signer=kms", "registerwerk.wallet.kms.provider=aws")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().doesNotHaveBean(KmsSigningClient.class);
                    KmsSignerService service = ctx.getBean(KmsSignerService.class);
                    assertThat(service.isEnabled()).isTrue();
                    assertThat(service.providerAvailable()).isFalse();
                });
    }

    @Test
    @DisplayName("an unknown signer value fails the startup instead of silently using software keys")
    void unknownSignerFails() {
        runner.withPropertyValues("registerwerk.wallet.signer=kmz")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
