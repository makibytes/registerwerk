package de.makibytes.registerwerk.application.customer;

import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates sequential entity and asset numbers scoped to the current calendar year.
 *
 * <p>In production this counter should be backed by a database sequence; here it uses
 * an in-memory AtomicLong initialised at startup (resets on restart, suitable for dev).
 */
@Component
public class EntityNumberGenerator {

    private final AtomicLong entityCounter = new AtomicLong(0L);
    private final AtomicLong assetCounter = new AtomicLong(0L);

    /**
     * Generates a unique entity number in the format {@code ENT-YYYY-NNNNNN}.
     *
     * @return e.g. "ENT-2026-000001"
     */
    public String generateEntityNumber() {
        int year = Year.now().getValue();
        long seq = entityCounter.incrementAndGet();
        return String.format("ENT-%d-%06d", year, seq);
    }

    /**
     * Generates a unique asset number in the format {@code AST-YYYY-NNNNNN}.
     *
     * @return e.g. "AST-2026-000001"
     */
    public String generateAssetNumber() {
        int year = Year.now().getValue();
        long seq = assetCounter.incrementAndGet();
        return String.format("AST-%d-%06d", year, seq);
    }
}
