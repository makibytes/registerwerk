package de.makibytes.registerwerk.registerstatement.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** {@code registerwerk_register_statements_refused_total{reason}}: statements refused on purpose (9A-05). */
final class StatementRefusalMetrics {

    private StatementRefusalMetrics() {}

    /** Refused because the asset's holder sync is BLOCKED (register unreconciled). */
    static Counter unreconciled(MeterRegistry meters) {
        return meters.counter("registerwerk_register_statements_refused_total", "reason", "unreconciled");
    }
}
