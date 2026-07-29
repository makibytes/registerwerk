package de.makibytes.registerwerk.regreporting.internal;

import de.makibytes.registerwerk.regreporting.api.SubmissionGateway;
import de.makibytes.registerwerk.regreporting.api.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Phase 5 findings #6 (audit trail / actor threading) and #9 (on-demand generation
 * previously silently ignored the requested period and always used "now").
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Dac8ExportService unit tests")
class Dac8ExportServiceTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private RegReportSubmissions submissions;
    @Mock private S3DocumentStore documentStore;
    @Mock private SubmissionGateway submissionGateway;
    @Mock private ReportingProperties reportingProperties;

    private Dac8ExportService service;

    @BeforeEach
    void setUp() {
        when(reportingProperties.isPrototypeEnabled()).thenReturn(true);
        service = new Dac8ExportService(jdbc, submissions, documentStore, submissionGateway, reportingProperties);
    }

    private static Map<String, Object> holdingRow() {
        return Map.of(
                "investor_id", UUID.randomUUID(), "entity_number", "E-1",
                "registration_country", "DE", "isin", "DE000TEST0001",
                "name", "Test Bond", "token_standard", "ERC20",
                "total_nominal", java.math.BigDecimal.TEN, "tx_count", 3L);
    }

    @Test
    @DisplayName("generateAnnualCarf(year, ...) uses the explicitly requested year, not now()-1")
    void generateAnnualCarf_usesRequestedYear() {
        UUID actorId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(holdingRow()));
        when(submissions.persist(anyString(), anyString(), any(), any(), any())).thenReturn(UUID.randomUUID());
        when(documentStore.store(any(), anyString(), anyString(), any(), any())).thenReturn("key");
        when(submissionGateway.submit(any(), anyString(), anyString(), any()))
                .thenReturn(SubmissionResult.transportedUnverified("REF-1"));

        service.generateAnnualCarf(2019, actorId, "REGISTRY_ADMIN");

        ArgumentCaptor<LocalDate> periodStart = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> periodEnd = ArgumentCaptor.forClass(LocalDate.class);
        verify(submissions, org.mockito.Mockito.atLeastOnce())
                .persist(anyString(), anyString(), periodStart.capture(), periodEnd.capture(), org.mockito.ArgumentMatchers.eq(actorId));
        assertThat(periodStart.getValue()).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(periodEnd.getValue()).isEqualTo(LocalDate.of(2019, 12, 31));

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(submissionGateway, org.mockito.Mockito.atLeastOnce())
                .submit(any(), anyString(), anyString(), payload.capture());
        String xml = new String(payload.getValue(), StandardCharsets.UTF_8);
        assertThat(xml)
                .contains("DRAFT_UNVALIDATED", "urn:registerwerk:prototype:carf:draft-unvalidated:v1")
                .doesNotContain("urn:oecd:ties:carf");
    }

    @Test
    @DisplayName("the actor threads through to the submission result handling")
    void generateAnnualCarf_threadsActorToApplyResult() {
        UUID actorId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(holdingRow()));
        when(submissions.persist(anyString(), anyString(), any(), any(), any())).thenReturn(submissionId);
        when(documentStore.store(any(), anyString(), anyString(), any(), any())).thenReturn("key");
        when(submissionGateway.submit(any(), anyString(), anyString(), any()))
                .thenReturn(SubmissionResult.transportedUnverified("REF-1"));

        service.generateAnnualCarf(2020, actorId, "REGISTRY_ADMIN");

        verify(submissions, org.mockito.Mockito.atLeastOnce())
                .markTransportedUnverified(org.mockito.ArgumentMatchers.eq(submissionId), anyString(),
                        org.mockito.ArgumentMatchers.eq(actorId), org.mockito.ArgumentMatchers.eq("REGISTRY_ADMIN"));
    }

    @Test
    @DisplayName("on-demand generation is unavailable unless the draft prototype is explicitly enabled")
    void generateAnnualCarf_disabledByDefault() {
        when(reportingProperties.isPrototypeEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.generateAnnualCarf(2025, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT_UNVALIDATED");
    }

    @Test
    @DisplayName("scheduled generation is a no-op while the prototype is disabled")
    void scheduledGenerateAnnualCarf_disabledDoesNothing() {
        when(reportingProperties.isPrototypeEnabled()).thenReturn(false);

        service.generateAnnualCarf();

        verifyNoInteractions(jdbc, submissions, documentStore, submissionGateway);
    }
}
