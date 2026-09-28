package de.makibytes.registerwerk.shared;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The register's calendar (T3-05/T3-06). Record, payment and announcement dates are calendar days
 * in the register's zone ({@code registerwerk.register.time-zone}, default {@code Europe/Berlin}),
 * not in the JVM's zone — containers run in UTC, which made "today" roll over an hour or two late
 * and put the record-date cut-off at the wrong instant.
 *
 * <p>Corporate-action jobs take "today" from here and schedule their crons in the same zone
 * ({@code @Scheduled(zone = "${registerwerk.register.time-zone:Europe/Berlin}")}).
 */
@Component
public class RegisterClock {

    private final Clock clock;
    private final ZoneId registerZone;

    @Autowired
    public RegisterClock(Clock clock, @Value("${registerwerk.register.time-zone:Europe/Berlin}") String zone) {
        this(clock, ZoneId.of(zone));
    }

    public RegisterClock(Clock clock, ZoneId registerZone) {
        this.clock = clock;
        this.registerZone = registerZone;
    }

    public ZoneId registerZone() {
        return registerZone;
    }

    /** The current calendar day in the register's zone. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(registerZone));
    }

    public Instant now() {
        return clock.instant();
    }

    /**
     * The exclusive end of {@code date} in the register's zone — the start of the following day.
     * A record-date cut-off counts everything strictly before this instant.
     */
    public Instant endOfDay(LocalDate date) {
        return date.plusDays(1).atStartOfDay(registerZone).toInstant();
    }
}
