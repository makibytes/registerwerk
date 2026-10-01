package de.makibytes.registerwerk.audit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** V36 on a database that already holds audit rows: nothing rewritten, old rows stay canon_version 1. */
@Testcontainers
@DisplayName("V36 migrates existing audit rows without rewriting them")
class AuditV36MigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Test
    void existingRowsKeepLegacyVersion() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("32").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("""
                INSERT INTO audit_event (event_type, subject_type, subject_id, actor_role, sequence_no, entry_hash)
                VALUES ('OLD', 'T', gen_random_uuid(), 'SYSTEM', 1, decode('00', 'hex'))
                """);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject("SELECT canon_version FROM audit_event WHERE event_type = 'OLD'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT recorded_at FROM audit_event WHERE event_type = 'OLD'", java.sql.Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_chain_meta WHERE signing_from_seq IS NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.role_table_grants
                WHERE grantee = 'registerwerk_app' AND table_name = 'audit_event' AND privilege_type IN ('UPDATE','DELETE')
                """, Integer.class)).isZero();
    }
}
