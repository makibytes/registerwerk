package de.makibytes.registerwerk.regreporting.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Verifies : no monitor existed for submissions stuck without a real ack. */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegReportStalenessMonitor unit tests")
class RegReportStalenessMonitorTest {

    @Mock private JdbcTemplate jdbc;

    private ReportingProperties props;
    private SimpleMeterRegistry meterRegistry;
    private RegReportStalenessMonitor monitor;

    @BeforeEach
    void setUp() {
        props = new ReportingProperties();
        props.setStaleSubmissionThresholdDays(5);
        meterRegistry = new SimpleMeterRegistry();
        monitor = new RegReportStalenessMonitor(jdbc, props, meterRegistry);
    }

    @Test
    @DisplayName("queries for transported drafts lacking verified authority evidence")
    void checkStaleSubmissions_queriesWithConfiguredThreshold() {
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(UUID.class), any(Instant.class)))
                .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        monitor.checkStaleSubmissions();

        org.mockito.Mockito.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("TRANSPORTED_UNVERIFIED"),
                org.mockito.ArgumentMatchers.eq(UUID.class), any(Instant.class));
    }

    @Test
    @DisplayName("no stale submissions found does not error")
    void checkStaleSubmissions_emptyResult_doesNotThrow() {
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(UUID.class), any(Instant.class)))
                .thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatCode(monitor::checkStaleSubmissions).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("stale-submissions gauge reflects a live count (alerting metrics)")
    void staleSubmissionsGauge_reflectsLiveCount() {
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Long.class), any(Instant.class)))
                .thenReturn(3L);

        double value = meterRegistry.get("registerwerk_regreport_stale_submissions_total").gauge().value();

        assertThat(value).isEqualTo(3.0);
    }
}
