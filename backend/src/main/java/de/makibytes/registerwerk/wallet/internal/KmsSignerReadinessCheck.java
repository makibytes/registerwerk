package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.shared.ProductionMode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Startup readiness of the cloud-KMS signer (T7-05). With {@code registerwerk.wallet.signer=kms} the
 * provider must be supported, {@code registerwerk.wallet.kms.key-version} must be set and well-formed,
 * and the key must answer a public-key request within {@code health-check-timeout} (this also proves
 * credentials/workload identity and the secp256k1 algorithm). Any failure is an ERROR that stops the
 * boot in production mode and a WARN otherwise. Gauge {@code registerwerk_wallet_kms_signer_ready}:
 * 1 ready, 0 enabled but failing, -1 not enabled.
 */
@Component
class KmsSignerReadinessCheck implements ApplicationRunner, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(KmsSignerReadinessCheck.class);

    private final KmsSignerService service;
    private final AtomicInteger ready = new AtomicInteger(-1);
    private Environment environment;

    KmsSignerReadinessCheck(KmsSignerService service, KmsProperties properties, MeterRegistry registry) {
        this.service = service;
        Gauge.builder("registerwerk_wallet_kms_signer_ready", ready, AtomicInteger::get)
                .description("1 if the cloud-KMS signer is configured and its key answered the startup probe, "
                        + "0 if enabled but failing, -1 if not enabled")
                .register(registry);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        check(environment != null && ProductionMode.resolve(environment));
    }

    void check(boolean productionMode) {
        if (!service.isEnabled()) {
            ready.set(-1);
            return;
        }
        List<String> problems = new ArrayList<>();
        String keyReference = service.configuredKeyReference();
        if (!service.providerAvailable()) {
            problems.add("no KMS signing client for provider '" + service.provider()
                    + "' (registerwerk.wallet.kms.provider; supported: gcp)");
        } else if (keyReference.isBlank()) {
            problems.add("registerwerk.wallet.kms.key-version is not set");
        } else if (!service.isValidKeyReference(keyReference)) {
            problems.add("registerwerk.wallet.kms.key-version '" + keyReference + "' is not a valid "
                    + service.provider() + " key reference");
        } else {
            try {
                String address = service.probe(keyReference, service.healthCheckTimeout());
                log.info("KMS signer ready: provider={} key={} address={}", service.provider(), keyReference, address);
            } catch (RuntimeException e) {
                problems.add("KMS key '" + keyReference + "' is not usable: " + e.getMessage());
            }
        }
        ready.set(problems.isEmpty() ? 1 : 0);
        if (problems.isEmpty()) {
            return;
        }
        String message = "KMS signer: " + String.join("; ", problems);
        if (productionMode) {
            throw new IllegalStateException(message);
        }
        log.warn("{} (non-production: continuing; signing with KMS wallets will fail until fixed)", message);
    }
}
