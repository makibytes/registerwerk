package de.makibytes.registerwerk.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-07: the V1 {@code chk_asset_status} never listed TRANSFERRED_OUT, so completing a register
 * handover would have died on the constraint. V14 adds it together with TRANSFER_PENDING and the
 * new handover/inspection/statement/erasure columns.
 */
@Testcontainers
@DisplayName("V14 register handover freeze migration")
class RegisterHandoverFreezeMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Test
    @DisplayName("assetStatusConstraintAcceptsTransferStatusesOnlyFromV14")
    void assetStatusConstraintAcceptsTransferStatusesOnlyFromV14() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("13").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        String before = constraint(jdbc);
        assertThat(before).doesNotContain("TRANSFERRED_OUT").doesNotContain("TRANSFER_PENDING");

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        assertThat(constraint(jdbc)).contains("TRANSFERRED_OUT").contains("TRANSFER_PENDING");
        assertThat(columns(jdbc, "register_transfer")).contains("previous_asset_status", "register_content_hash",
                "successor_onchain_address", "onchain_handovers");
        assertThat(columns(jdbc, "register_inspection_request")).contains("claim_verified");
        assertThat(columns(jdbc, "register_statement")).contains("delivery_error_code");
        assertThat(columns(jdbc, "erasure_request")).contains("retained_notice_channel");
    }

    private static String constraint(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_asset_status'", String.class);
    }

    private static java.util.List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, table);
    }
}
