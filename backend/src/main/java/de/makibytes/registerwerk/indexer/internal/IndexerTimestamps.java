package de.makibytes.registerwerk.indexer.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Ingestion guard for source-supplied {@code occurred_at} (7A-11): a node or subgraph timestamp more than a day
 * in the future would land in a {@code *_default} partition and later block creating its month. Such a value is
 * clamped to ingestion time, logged and counted; the raw source timestamp stays in the transfer's raw data.
 * Past timestamps (backfills) are untouched, record-date snapshots depend on the block's own time.
 */
@Component
public class IndexerTimestamps {

    private static final Logger log = LoggerFactory.getLogger(IndexerTimestamps.class);
    static final Duration MAX_FUTURE_SKEW = Duration.ofDays(1);
    private static volatile MeterRegistry registry;

    IndexerTimestamps(MeterRegistry meterRegistry) {
        registry = meterRegistry;
    }

    static Instant clampFuture(Instant source, String chain) {
        return clampFuture(source, chain, Instant.now());
    }

    static Instant clampFuture(Instant source, String chain, Instant now) {
        if (source == null || !source.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            return source;
        }
        log.warn("Indexed timestamp {} for chain {} is more than {} in the future - clamped to ingestion time {}",
                source, chain, MAX_FUTURE_SKEW, now);
        MeterRegistry r = registry;
        if (r != null) {
            r.counter("registerwerk_indexer_future_timestamp_total", "chain", String.valueOf(chain)).increment();
        }
        return now;
    }
}
