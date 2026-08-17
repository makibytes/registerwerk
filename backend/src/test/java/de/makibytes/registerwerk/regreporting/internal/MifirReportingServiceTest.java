package de.makibytes.registerwerk.regreporting.internal;

import de.makibytes.registerwerk.regreporting.api.SubmissionGateway;
import de.makibytes.registerwerk.regreporting.api.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies audit trail / actor threading and on-demand generation
 * previously silently ignored the requested reporting date and always used "yesterday").
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MifirReportingService unit tests")
class MifirReportingServiceTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private RegReportSubmissions submissions;
    @Mock private S3DocumentStore documentStore;
    @Mock private SubmissionGateway submissionGateway;
    @Mock private ReportingProperties reportingProperties;

    private MifirReportingService service;

    @BeforeEach
    void setUp() {
        when(reportingProperties.isPrototypeEnabled()).thenReturn(true);
        service = new MifirReportingService(jdbc, submissions, documentStore, submissionGateway, reportingProperties);
    }

    private static Map<String, Object> tradeRow() {
        return Map.of(
                "id", UUID.randomUUID(), "asset_id", UUID.randomUUID(),
                "buyer_entity_id", UUID.randomUUID(), "seller_entity_id", UUID.randomUUID(),
                "price", java.math.BigDecimal.TEN, "quantity", java.math.BigDecimal.ONE,
                "executed_at", java.sql.Timestamp.from(java.time.Instant.now()),
                "isin", "DE000TEST0001", "name", "Test Bond");
    }

    @Test
    @DisplayName("generateDailyReport(date, ...) uses the explicitly requested date, not now()-1")
    void generateDailyReport_usesRequestedDate() {
        LocalDate requestedDate = LocalDate.of(2025, 3, 15);
        UUID actorId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(requestedDate), anyString())).thenReturn(List.of(tradeRow()));
        when(submissions.persist(anyString(), anyString(), eq(requestedDate), eq(requestedDate), eq(actorId)))
                .thenReturn(submissionId);
        when(documentStore.store(any(), anyString(), anyString(), eq(requestedDate), any())).thenReturn("key");
        when(submissionGateway.submit(any(), anyString(), anyString(), any()))
                .thenReturn(SubmissionResult.transportedUnverified("REF-1"));

        service.generateDailyReport(requestedDate, actorId, "REGISTRY_ADMIN");

        verify(submissions, atLeastOnce())
                .persist(anyString(), anyString(), eq(requestedDate), eq(requestedDate), eq(actorId));
        verify(submissions, atLeastOnce())
                .markTransportedUnverified(eq(submissionId), anyString(), eq(actorId), eq("REGISTRY_ADMIN"));

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(submissionGateway, atLeastOnce()).submit(any(), anyString(), anyString(), payload.capture());
        String xml = new String(payload.getValue(), StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(xml)
                .contains("DRAFT_UNVALIDATED", "urn:registerwerk:prototype:mifir:draft-unvalidated:v1")
                .doesNotContain("urn:iso:std:iso:20022");
    }

    @Test
    @DisplayName("on-demand generation is unavailable unless the draft prototype is explicitly enabled")
    void generateDailyReport_disabledByDefault() {
        when(reportingProperties.isPrototypeEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.generateDailyReport(
                LocalDate.of(2025, 3, 15), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT_UNVALIDATED");
    }

    @Test
    @DisplayName("scheduled generation is a no-op while the prototype is disabled")
    void scheduledGenerateDailyReport_disabledDoesNothing() {
        when(reportingProperties.isPrototypeEnabled()).thenReturn(false);

        service.generateDailyReport();

        verifyNoInteractions(jdbc, submissions, documentStore, submissionGateway);
    }
}
