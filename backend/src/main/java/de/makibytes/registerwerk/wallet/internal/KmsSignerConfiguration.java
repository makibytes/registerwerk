package de.makibytes.registerwerk.wallet.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Wiring of the cloud-KMS signer (T7-05). {@link KmsSignerService} always exists (like
 * {@link Pkcs11HsmService}) and reports {@code isEnabled()}; a provider client is created only for
 * {@code registerwerk.wallet.signer=kms}, selected by {@code registerwerk.wallet.kms.provider}. A new
 * provider (AWS KMS) is one more {@code @Bean} here plus a {@link KmsSigningClient} implementation;
 * an unknown provider yields no client and is reported by {@link KmsSignerReadinessCheck}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KmsProperties.class)
class KmsSignerConfiguration {

    @Bean
    KmsSignerService kmsSignerService(KmsProperties properties, ObjectProvider<KmsSigningClient> client,
                                      MeterRegistry registry) {
        return new KmsSignerService(properties, Optional.ofNullable(client.getIfAvailable()), registry);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "registerwerk.wallet.signer", havingValue = "kms")
    static class Providers {

        @Bean
        @ConditionalOnProperty(name = "registerwerk.wallet.kms.provider", havingValue = "gcp", matchIfMissing = true)
        GcpKmsSigningClient gcpKmsSigningClient(KmsProperties properties) {
            // The service enforces the per-attempt deadline; the client's own deadline is a 1 s backstop.
            return new GcpKmsSigningClient(properties.getKms().getTimeout().plusSeconds(1));
        }
    }
}
