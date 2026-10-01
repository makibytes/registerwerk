package de.makibytes.registerwerk.dora.internal;

import de.makibytes.registerwerk.TestJwt;
import de.makibytes.registerwerk.audit.events.AuditDeadLetteredEvent;
import de.makibytes.registerwerk.dora.api.IctIncident;
import de.makibytes.registerwerk.dora.api.IctIncidentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** K5 / 6-13 against a real database: clocks, reclassification, write-once reports, status-independent monitoring, triggers, auto draft. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("DORA incident controls integration test (K5)")
class DoraIncidentControlsIT {

    private static final String SECRET = "integration-test-jwt-secret-32-bytes!!";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.dev-secret", () -> SECRET);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired IctIncidentRepository incidents;
    @Autowired DoraService doraService;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @LocalServerPort int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<Object> req(Object body, boolean stepUp) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(TestJwt.mint(SECRET, UUID.randomUUID(), stepUp, null, null, "REGISTRY_ADMIN"));
        return new HttpEntity<>(body, h);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body, boolean stepUp, HttpStatus expected) {
        ResponseEntity<Map> r = rest.exchange(url(path), HttpMethod.POST, req(body, stepUp), Map.class);
        assertThat(r.getStatusCode()).as(path).isEqualTo(expected);
        return r.getBody();
    }

    private UUID createIncident(String severity, Instant awareness, String reason) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("title", "IT incident", "description", "d",
                "category", "SYSTEM_OUTAGE", "severity", severity));
        if (awareness != null) body.put("awarenessAt", awareness.toString());
        if (reason != null) body.put("classificationReason", reason);
        return UUID.fromString((String) post("/api/v1/dora/incidents", body, false, HttpStatus.CREATED).get("id"));
    }

    private ResponseEntity<Map> patchStatus(UUID id, String status) {
        return rest.exchange(url("/api/v1/dora/incidents/" + id + "/status"), HttpMethod.PATCH,
                req(Map.of("status", status), false), Map.class);
    }

    @Test
    @DisplayName("a MAJOR incident forced to CLOSED stays in breach monitoring (fails before: it vanished by status)")
    void closedMajorStaysOverdue() {
        UUID id = createIncident("MAJOR", Instant.now().minus(30, ChronoUnit.HOURS), "duration threshold");
        jdbc.update("UPDATE ict_incident SET status = 'CLOSED' WHERE id = ?", id);

        Instant now = Instant.now();
        assertThat(incidents.findOverdueInitialReports(now)).extracting(IctIncident::getId).contains(id);
        assertThat(incidents.findOverdueClassificationReports(now.plus(5, ChronoUnit.HOURS))).extracting(IctIncident::getId).contains(id);
        assertThat(incidents.findOverdueFinalReports(now.plus(31, ChronoUnit.DAYS))).extracting(IctIncident::getId).contains(id);
        assertThat(doraService.listOpen()).extracting(IctIncident::getId).contains(id);
    }

    @Test
    @DisplayName("classify HIGH -> MAJOR needs step-up and a reason, then starts the clocks")
    void classifyEscalation() {
        UUID id = createIncident("HIGH", Instant.now().minus(2, ChronoUnit.HOURS), null);
        post("/api/v1/dora/incidents/" + id + "/classify", Map.of("severity", "MAJOR", "reason", "r"), false, HttpStatus.FORBIDDEN);
        post("/api/v1/dora/incidents/" + id + "/classify", Map.of("severity", "MAJOR", "reason", " "), true, HttpStatus.BAD_REQUEST);

        Map<String, Object> r = post("/api/v1/dora/incidents/" + id + "/classify",
                Map.of("severity", "MAJOR", "reason", "clients affected", "criteria", Map.of("clientsAffected", "1200")),
                true, HttpStatus.OK);

        assertThat(r.get("severity")).isEqualTo("MAJOR");
        assertThat(r.get("classifiedAt")).isNotNull();
        assertThat(r.get("classificationDeadline")).isNotNull();
        assertThat(r.get("classificationReason")).isEqualTo("clients affected");
        assertThat(Instant.parse((String) r.get("initialReportDeadline")))
                .isEqualTo(Instant.parse((String) r.get("awarenessAt")).plus(24, ChronoUnit.HOURS));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE subject_id = ? AND event_type = 'ICT_INCIDENT_CLASSIFIED'",
                Integer.class, id)).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("reports are append-only: second report keeps the first timestamp/ref; PATCH cannot report or close without evidence")
    void reportsWriteOnce() {
        UUID id = createIncident("MAJOR", null, "r");
        assertThat(patchStatus(id, "REPORTED_TO_AUTHORITY").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> close = patchStatus(id, "CLOSED");
        assertThat(close.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(close.getBody().get("message"))).contains("INITIAL_REPORT_MISSING");

        post("/api/v1/dora/incidents/" + id + "/report-to-authority",
                Map.of("authorityRef", "BAFIN-1", "reportType", "INITIAL"), false, HttpStatus.OK);
        Instant first = jdbc.queryForObject("SELECT initial_reported_at FROM ict_incident WHERE id = ?", Instant.class, id);
        Map<String, Object> second = post("/api/v1/dora/incidents/" + id + "/report-to-authority",
                Map.of("authorityRef", "BAFIN-2", "reportType", "INITIAL", "note", "correction"), false, HttpStatus.OK);

        assertThat(jdbc.queryForObject("SELECT initial_reported_at FROM ict_incident WHERE id = ?", Instant.class, id)).isEqualTo(first);
        assertThat(second.get("authorityRef")).isEqualTo("BAFIN-1");
        assertThat((List<?>) second.get("reports")).hasSize(2);
        assertThat(second.get("intermediateReportDeadline")).isNotNull();
        // legacy flag still works for a final report, which needs the initial one
        Map<String, Object> fin = post("/api/v1/dora/incidents/" + id + "/report-to-authority",
                Map.of("authorityRef", "BAFIN-F", "isFinalReport", true), false, HttpStatus.OK);
        assertThat(fin.get("status")).isEqualTo("REPORTED_TO_AUTHORITY");
        assertThat(fin.get("finalReportedAt")).isNotNull();
        // missing evidence
        post("/api/v1/dora/incidents/" + id + "/report-to-authority", Map.of("authorityRef", " "), false, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("database refuses to update/delete report rows and to rewrite awareness / first-submission columns")
    void triggers() {
        UUID id = createIncident("MAJOR", null, "r");
        post("/api/v1/dora/incidents/" + id + "/report-to-authority", Map.of("authorityRef", "R1"), false, HttpStatus.OK);

        assertThatThrownBy(() -> jdbc.update("UPDATE ict_incident_report SET authority_ref = 'x' WHERE incident_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ict_incident_report WHERE incident_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE ict_incident SET initial_reported_at = now() WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("write-once");
        assertThatThrownBy(() -> jdbc.update("UPDATE ict_incident SET authority_ref = 'other' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("write-once");
        assertThatThrownBy(() -> jdbc.update("UPDATE ict_incident SET awareness_at = now() - interval '1 day' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("export labels the final deadline as one month and never as 72h")
    void exportLabels() {
        UUID id = createIncident("MAJOR", null, "r");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestJwt.mint(SECRET, UUID.randomUUID(), false, null, null, "REGISTRY_ADMIN"));
        String csv = rest.exchange(url("/api/v1/dora/incidents/" + id + "/authority-report"), HttpMethod.GET,
                new HttpEntity<>(h), String.class).getBody();
        assertThat(csv).contains("Final report deadline (1 month from awareness)").doesNotContain("Final report deadline (72h)");
    }

    @Test
    @DisplayName("AuditDeadLetteredEvent opens exactly one unclassified draft incident (K4 hook)")
    void deadLetterOpensDraft() throws Exception {
        UUID publicationId = UUID.randomUUID();
        tx.executeWithoutResult(s -> publisher.publishEvent(
                new AuditDeadLetteredEvent(publicationId, "listener", "SOME_EVENT", 20)));
        tx.executeWithoutResult(s -> publisher.publishEvent(
                new AuditDeadLetteredEvent(UUID.randomUUID(), "listener", "OTHER_EVENT", 20)));

        long deadline = System.currentTimeMillis() + 15_000;
        Integer n = 0;
        while (System.currentTimeMillis() < deadline) {
            n = jdbc.queryForObject("SELECT count(*) FROM ict_incident WHERE source_event_type = 'AUDIT_DEAD_LETTERED'", Integer.class);
            if (n != null && n > 0) break;
            Thread.sleep(200);
        }
        Thread.sleep(1500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ict_incident WHERE source_event_type = 'AUDIT_DEAD_LETTERED'", Integer.class))
                .isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT severity, classification_pending, classified_at FROM ict_incident WHERE source_event_type = 'AUDIT_DEAD_LETTERED'");
        assertThat(row.get("classification_pending")).isEqualTo(true);
        assertThat(row.get("classified_at")).isNull();
    }
}
