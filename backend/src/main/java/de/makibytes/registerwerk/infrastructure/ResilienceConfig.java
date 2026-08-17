package de.makibytes.registerwerk.infrastructure;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared Resilience4j registries (Phase 3) for outbound RPC/HTTP calls, each bound to
 * {@link MeterRegistry} so retry/circuit-breaker/bulkhead state is Prometheus-visible without any
 * extra wiring at the call site.
 *
 * <p>Deliberately the programmatic API (a caller does
 * {@code retryRegistry.retry(name, config)}, not {@code @Retry(name = "...")}) rather than the
 * {@code resilience4j-spring-boot3} annotation starter — see the dependency comment in
 * {@code pom.xml} for why. Individual call sites own their own {@code RetryConfig}/
 * {@code CircuitBreakerConfig}/{@code BulkheadConfig} (failure characteristics genuinely differ
 * per integration — a GraphQL query and a per-address fan-out don't want the same policy), this
 * class only owns the registries and their metrics binding.
 *
 * <p><strong>Scope (Phase 3):</strong> applied to {@code GraphNodeClient} (retry + circuit
 * breaker around the HTTP call) and the Starknet/Stellar per-address fan-out (bulkhead, capping
 * concurrency against the shared ForkJoinPool). Not applied blanket across every outbound
 * RPC/HTTP call in the codebase (Web3j chain calls, screening/travel-rule providers, trading
 * venues, the Zama relayer) — that would be a much larger, separately-scoped effort; this phase
 * targets the specific gaps the production-readiness review named.
 */
@Configuration
class ResilienceConfig {

    @Bean
    RetryRegistry retryRegistry(MeterRegistry meterRegistry) {
        RetryRegistry registry = RetryRegistry.ofDefaults();
        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meterRegistry) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    BulkheadRegistry bulkheadRegistry(MeterRegistry meterRegistry) {
        BulkheadRegistry registry = BulkheadRegistry.ofDefaults();
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);
        return registry;
    }
}
