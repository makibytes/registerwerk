package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T6-17: the real two-login topology. Flyway runs as the schema owner ("test", the migrator); the
 * application datasource is the DML-only runtime login {@code registerwerk_app}, created by the very
 * same script compose and Helm use ({@code postgres-init/roles/ensure-runtime-role.sh}). Proves that
 * the whole app context boots and appends/verifies the audit chain as the restricted role, and that the
 * V36 REVOKEs and the ownership boundary now bind it.
 */
@ApplicationModuleTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Runtime role split (T6-17)")
class AuditRuntimeRoleSplitIT {

    static final String RUNTIME_USER = "registerwerk_app";
    static final String RUNTIME_PASSWORD = "runtime-it-password";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    static {
        postgres.start();
        try {
            Path script = Path.of("..", "postgres-init", "roles", "ensure-runtime-role.sh").toAbsolutePath().normalize();
            postgres.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/ensure-runtime-role.sh");
            var result = postgres.execInContainer("sh", "-c",
                    "POSTGRES_USER=" + postgres.getUsername() + " POSTGRES_DB=" + postgres.getDatabaseName()
                            + " DB_APP_USER=" + RUNTIME_USER + " DB_APP_PASSWORD=" + RUNTIME_PASSWORD
                            + " sh /tmp/ensure-runtime-role.sh");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("role script failed: " + result.getStderr());
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditChainVerificationService verificationService;

    private static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException se) {
                return se.getSQLState();
            }
        }
        return null;
    }

    private void assertDenied(String sql) {
        assertThatThrownBy(() -> jdbc.execute(sql)).isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).as(sql).isEqualTo("42501"));
    }

    @Test
    @DisplayName("the app runs as the restricted login: audit appends and chain verification work")
    void appRunsAsRuntimeRole(Scenario scenario) {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(RUNTIME_USER);
        UUID subjectId = UUID.randomUUID();
        scenario.publish(new KycApprovedEvent(subjectId, UUID.randomUUID(), "REGISTRY_ADMIN", Map.of("seq", "0")))
                .andWaitForStateChange(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE subject_id = ? AND event_type = 'KYC_APPROVED'",
                        Integer.class, subjectId))
                .andVerify(count -> assertThat(count).isGreaterThan(0));
        verificationService.verify();
        assertThat(verificationService.health().getStatus().getCode()).isEqualTo("UP");
        // a table added by a LATER migration (V54) is reachable: default privileges, not just V1's snapshot grant
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_request", Integer.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shedlock", Integer.class)).isNotNull();
    }

    @Test
    @DisplayName("WORM: UPDATE/DELETE/TRUNCATE/DDL on audit_event and its partitions are denied to the runtime login")
    void wormPrivilegesBind() {
        assertDenied("UPDATE audit_event SET event_type = 'X'");
        assertDenied("DELETE FROM audit_event");
        assertDenied("TRUNCATE audit_event");
        assertDenied("TRUNCATE audit_event_default");
        assertDenied("UPDATE audit_chain_anchor SET sig = NULL");
        assertDenied("ALTER TABLE audit_event DISABLE TRIGGER ALL");
        assertDenied("DROP TABLE audit_event_default");
        assertDenied("DROP TRIGGER trg_audit_event_immutable ON audit_event");
        assertDenied("CREATE TABLE runtime_ddl_probe (id int)");
        assertDenied("ALTER TABLE token_transfer ADD COLUMN runtime_probe int");
    }

    @Test
    @DisplayName("readiness: the runtime role is not an owner and reports so")
    void readinessIsMeaningful() {
        assertThat(jdbc.queryForObject("""
                SELECT pg_has_role(current_user, c.relowner, 'USAGE') FROM pg_class c WHERE c.oid = 'audit_event'::regclass
                """, Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("partition maintenance works for the runtime login and cannot be abused on audit_event")
    void partitionMaintenanceWorksViaDefinerFunctions() {
        jdbc.execute("SELECT audit_event_ensure_partitions(30)");
        Integer partitions = jdbc.queryForObject(
                "SELECT count(*) FROM pg_inherits WHERE inhparent = 'audit_event'::regclass", Integer.class);
        assertThat(partitions).isGreaterThan(30);
        // partitions created through the definer function carry the V36 WORM guard for the runtime role
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'audit_event'::regclass
                  AND (has_table_privilege(current_user, c.oid, 'UPDATE')
                    OR has_table_privilege(current_user, c.oid, 'DELETE')
                    OR has_table_privilege(current_user, c.oid, 'TRUNCATE'))
                """, Integer.class)).isZero();
        jdbc.execute("SELECT rw_ensure_monthly_partitions('token_transfer', 'occurred_at', 30)");
        jdbc.execute("SELECT rw_ensure_monthly_partitions('blockchain_transaction', 'created_at', 30)");
        // the generic helper must not become a back door to create unguarded audit partitions
        assertThatThrownBy(() -> jdbc.execute("SELECT rw_ensure_monthly_partitions('audit_event', 'occurred_at', 40)"))
                .hasMessageContaining("audit");
        // detaching/retiring is migrator-only
        assertThatThrownBy(() -> jdbc.queryForList("SELECT rw_retire_partitions('token_transfer', 'occurred_at', 1)"))
                .isInstanceOf(DataAccessException.class);
    }
}
