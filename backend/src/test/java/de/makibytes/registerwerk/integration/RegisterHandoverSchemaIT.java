package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.MigratedDb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-07: completing a register handover needs TRANSFERRED_OUT (and the TRANSFER_PENDING freeze) in
 * {@code chk_asset_status}, plus the handover / inspection / statement / erasure columns.
 */
@Testcontainers
@DisplayName("Register handover schema")
class RegisterHandoverSchemaIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Test
    @DisplayName("the asset status constraint lists the transfer statuses and the handover columns exist")
    void assetStatusConstraintAcceptsTransferStatuses() {
        JdbcTemplate jdbc = MigratedDb.migrate(postgres).jdbc();

        String constraint = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_asset_status'", String.class);
        assertThat(constraint).contains("TRANSFERRED_OUT").contains("TRANSFER_PENDING");
        assertThat(columns(jdbc, "register_transfer")).contains("previous_asset_status", "register_content_hash",
                "successor_onchain_address", "onchain_handovers");
        assertThat(columns(jdbc, "register_inspection_request")).contains("claim_verified");
        assertThat(columns(jdbc, "register_statement")).contains("delivery_error_code");
        assertThat(columns(jdbc, "erasure_request")).contains("retained_notice_channel");
    }

    private static java.util.List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, table);
    }
}
