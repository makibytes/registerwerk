package de.makibytes.registerwerk.regreporting.internal;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the V17 to V18 correction of legacy gateway outcomes on PostgreSQL. */
@Testcontainers
@DisplayName("Regulatory reporting transport-status migration")
class RegReportingTransportStatusMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    @DisplayName("legacy authority-like statuses become transport-only while evidence is preserved")
    void legacyStatusesAreNormalizedWithoutLosingEvidence() throws Exception {
        migrateTo("17");

        Instant legacyTime = Instant.parse("2026-01-02T03:04:05Z");
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (String status : new String[]{
                "READY", "SUBMITTED", "PENDING_ACK", "REJECTED", "ACKNOWLEDGED", "ACCEPTED"}) {
            UUID id = UUID.randomUUID();
            ids.put(status, id);
            insertLegacyRow(id, status, legacyTime);
        }

        migrateTo(null);

        assertStatus(ids.get("READY"), "DRAFT_UNVALIDATED", null, null);
        assertStatus(ids.get("SUBMITTED"), "TRANSPORTED_UNVERIFIED", "legacy-SUBMITTED", legacyTime);
        assertStatus(ids.get("PENDING_ACK"), "TRANSPORTED_UNVERIFIED", "legacy-PENDING_ACK", legacyTime);
        assertStatus(ids.get("ACKNOWLEDGED"), "TRANSPORTED_UNVERIFIED", "legacy-ACKNOWLEDGED", legacyTime);
        assertStatus(ids.get("ACCEPTED"), "TRANSPORTED_UNVERIFIED", "legacy-ACCEPTED", legacyTime);
        assertStatus(ids.get("REJECTED"), "TRANSPORT_FAILED", "legacy-REJECTED", legacyTime);

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT transport_error, rejection_reason
                     FROM regreport_submission WHERE id = ?
                     """)) {
            statement.setObject(1, ids.get("REJECTED"));
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("transport_error")).isEqualTo("legacy transport error");
                assertThat(result.getString("rejection_reason")).isEqualTo("legacy transport error");
            }
        }
    }

    private static void migrateTo(String version) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        if (version != null) {
            configuration.target(MigrationVersion.fromVersion(version));
        }
        configuration.load().migrate();
    }

    private static void insertLegacyRow(UUID id, String status, Instant legacyTime) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     INSERT INTO regreport_submission
                       (id, report_type, jurisdiction, status, reporting_period_start,
                        reporting_period_end, submitted_at, submission_ref, rejection_reason)
                     VALUES (?, 'PROTOTYPE', 'TEST', ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, id);
            statement.setString(2, status);
            statement.setObject(3, LocalDate.of(2025, 1, 1));
            statement.setObject(4, LocalDate.of(2025, 12, 31));
            statement.setTimestamp(5, status.equals("READY") ? null : Timestamp.from(legacyTime));
            statement.setString(6, status.equals("READY") ? null : "legacy-" + status);
            statement.setString(7, status.equals("REJECTED") ? "legacy transport error" : null);
            statement.executeUpdate();
        }
    }

    private static void assertStatus(UUID id, String expectedStatus,
                                     String expectedRef, Instant expectedTime) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT status, transport_ref, transported_at, submission_ref, submitted_at
                     FROM regreport_submission WHERE id = ?
                     """)) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo(expectedStatus);
                assertThat(result.getString("transport_ref")).isEqualTo(expectedRef);
                assertThat(result.getString("submission_ref")).isEqualTo(expectedRef);
                if (expectedTime == null) {
                    assertThat(result.getTimestamp("transported_at")).isNull();
                } else {
                    assertThat(result.getTimestamp("transported_at").toInstant()).isEqualTo(expectedTime);
                    assertThat(result.getTimestamp("submitted_at").toInstant()).isEqualTo(expectedTime);
                }
            }
        }
    }
}
