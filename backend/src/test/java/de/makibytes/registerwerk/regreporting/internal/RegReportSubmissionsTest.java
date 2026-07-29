package de.makibytes.registerwerk.regreporting.internal;

import de.makibytes.registerwerk.regreporting.events.RegReportTransportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * Verifies the Phase 5 finding #6 fix: regulatory report submissions previously had no audit
 * trail and left document_hash/generated_by unpopulated. RegReportSubmissions is the single
 * chokepoint every reporting service funnels through, so these tests cover all of them.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegReportSubmissions audit-event unit tests")
class RegReportSubmissionsTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private ApplicationEventPublisher events;

    private RegReportSubmissions submissions;

    @BeforeEach
    void setUp() {
        submissions = new RegReportSubmissions(jdbc, events);
    }

    @Test
    @DisplayName("persist records the triggering actor as generated_by")
    void persist_recordsGeneratedBy() {
        UUID actorId = UUID.randomUUID();

        UUID submissionId = submissions.persist("DAC8_CARF", "DE_BAFIN",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), actorId);

        assertThat(submissionId).isNotNull();
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("generated_by"),
                any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(actorId));
    }

    @Test
    @DisplayName("transport success stays unverified and carries the actor")
    void markTransportedUnverified_publishesEvent() {
        UUID submissionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        submissions.markTransportedUnverified(submissionId, "REF-1", actorId, "REGISTRY_ADMIN");

        ArgumentCaptor<RegReportTransportEvent> captor = ArgumentCaptor.forClass(RegReportTransportEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().submissionId()).isEqualTo(submissionId);
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().payload())
                .containsEntry("status", "TRANSPORTED_UNVERIFIED")
                .containsEntry("authorityReceiptVerified", false);
    }

    @Test
    @DisplayName("no transport publishes a truthful event with a null actor for scheduled runs")
    void markNotTransported_publishesEventForSystemRun() {
        UUID submissionId = UUID.randomUUID();

        submissions.markNotTransported(submissionId, "NOOP", null, "SYSTEM");

        ArgumentCaptor<RegReportTransportEvent> captor = ArgumentCaptor.forClass(RegReportTransportEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isNull();
        assertThat(captor.getValue().actorRole()).isEqualTo("SYSTEM");
        assertThat(captor.getValue().payload()).containsEntry("status", "NOT_TRANSPORTED");
    }

    @Test
    @DisplayName("transport failure is not represented as authority rejection")
    void markTransportFailed_publishesEvent() {
        UUID submissionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        submissions.markTransportFailed(submissionId, "SFTP unavailable", actorId, "REGISTRY_ADMIN");

        ArgumentCaptor<RegReportTransportEvent> captor = ArgumentCaptor.forClass(RegReportTransportEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().payload())
                .containsEntry("status", "TRANSPORT_FAILED")
                .containsEntry("reason", "SFTP unavailable");
    }
}
