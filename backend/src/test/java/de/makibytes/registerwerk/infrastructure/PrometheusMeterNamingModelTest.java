package de.makibytes.registerwerk.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Micrometer -> Prometheus naming rules that {@code scripts/check-alert-metrics.sh} models when it
 * checks every {@code registerwerk_*} name in alert rules and dashboards against the backend's meter
 * registrations. If a Micrometer upgrade changes one of these rules the guard's model is stale: update the
 * script together with this test.
 */
@DisplayName("Prometheus exposition names modelled by scripts/check-alert-metrics.sh")
class PrometheusMeterNamingModelTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @Test
    @DisplayName("a counter x is exposed as x_total (a literal that already ends in _total is not doubled)")
    void counterGetsTotalSuffix() {
        Counter.builder("registerwerk.model.events").register(registry).increment();
        Counter.builder("registerwerk_model_failures_total").register(registry).increment();

        String scrape = registry.scrape();
        assertThat(scrape).contains("registerwerk_model_events_total 1.0");
        assertThat(scrape).contains("registerwerk_model_failures_total 1.0");
        assertThat(scrape).doesNotContain("failures_total_total");
    }

    @Test
    @DisplayName("a gauge registered as x_total is exposed as x: an alert on x_total would never fire")
    void gaugeDropsTotalSuffix() {
        Gauge.builder("registerwerk_model_open_total", () -> 3.0).register(registry);

        String scrape = registry.scrape();
        assertThat(scrape).contains("registerwerk_model_open 3.0");
        assertThat(scrape).doesNotContain("registerwerk_model_open_total");
    }

    @Test
    @DisplayName("a timer x is exposed as x_seconds_count/_sum/_max, and x_seconds_bucket only with a histogram")
    void timerSuffixesAndBuckets() {
        Timer.builder("registerwerk.model.plain").register(registry).record(Duration.ofMillis(5));
        Timer.builder("registerwerk.model.histogram").publishPercentileHistogram().register(registry)
                .record(Duration.ofMillis(5));

        String scrape = registry.scrape();
        assertThat(scrape).contains("registerwerk_model_plain_seconds_count 1")
                .contains("registerwerk_model_plain_seconds_sum")
                .contains("registerwerk_model_plain_seconds_max")
                .doesNotContain("registerwerk_model_plain_seconds_bucket");
        assertThat(scrape).contains("registerwerk_model_histogram_seconds_bucket{le=");
    }
}
