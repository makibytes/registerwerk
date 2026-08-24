package de.makibytes.registerwerk.orgidentity.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reset-then-recount drift counter backing a Micrometer gauge, for reconciliation sweeps that
 * have no dedicated table to count against (unlike indexer/ChainDriftDetectionJob's
 * chain_drift_event) — shared by {@link OrgChainReconciliationService} and {@link
 * PermissionChainReconciliationService} so the gauge-registration boilerplate isn't copy-pasted
 * per job.
 */
class SweepDriftCounter {

    private final AtomicInteger count = new AtomicInteger(0);

    SweepDriftCounter(MeterRegistry meterRegistry, String gaugeName, String description) {
        Gauge.builder(gaugeName, count, AtomicInteger::get)
                .description(description)
                .register(meterRegistry);
    }

    void reset() {
        count.set(0);
    }

    void increment() {
        count.incrementAndGet();
    }
}
