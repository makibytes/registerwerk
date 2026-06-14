package de.makibytes.registerwerk.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the application {@link Clock}.
 *
 * <p>Services that make date-sensitive regulatory decisions (e.g. the MiCA
 * transitional-period cutoff in {@code CaspRegistryService}) must inject this
 * clock rather than calling {@code LocalDate.now()} directly, so tests can pin
 * the date deterministically.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
