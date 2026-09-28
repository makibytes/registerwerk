package de.makibytes.registerwerk.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-15: V10 normalises Sperrvermerk wallets already stored with a checksum/padded 0x address and
 * queues the ACTIVE ones for a one-shot on-chain freeze re-propagation. Migrates a real database to
 * V9, inserts pre-fix rows, then applies V10.
 */
@Testcontainers
@DisplayName("V10 holder_block wallet normalisation migration")
class HolderBlockWalletNormalisationMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Test
    @DisplayName("migrationNormalises: 0x wallets lowercased+trimmed, base58 untouched, ACTIVE changed rows queued")
    void migrationNormalises() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("9").load().migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        UUID checksumActive = insert(jdbc, "  0xAbCdEf0000000000000000000000000000000001 ", "ACTIVE");
        UUID checksumLifted = insert(jdbc, "0xAbCdEf0000000000000000000000000000000002", "LIFTED");
        UUID alreadyLower = insert(jdbc, "0xabcdef0000000000000000000000000000000003", "ACTIVE");
        UUID solana = insert(jdbc, "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", "ACTIVE");

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("10").load().migrate();

        assertThat(wallet(jdbc, checksumActive)).isEqualTo("0xabcdef0000000000000000000000000000000001");
        assertThat(wallet(jdbc, checksumLifted)).isEqualTo("0xabcdef0000000000000000000000000000000002");
        assertThat(wallet(jdbc, alreadyLower)).isEqualTo("0xabcdef0000000000000000000000000000000003");
        assertThat(wallet(jdbc, solana)).isEqualTo("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU");

        List<UUID> queued = jdbc.queryForList(
                "SELECT holder_block_id FROM holder_block_freeze_resync WHERE processed_at IS NULL", UUID.class);
        assertThat(queued).containsExactly(checksumActive);
    }

    private static UUID insert(JdbcTemplate jdbc, String wallet, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO holder_block (id, wallet_address, block_type, status, legal_basis, created_by) "
                + "VALUES (?, ?, 'GERICHTSBESCHLUSS', ?, 'court order', ?)", id, wallet, status, UUID.randomUUID());
        return id;
    }

    private static String wallet(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT wallet_address FROM holder_block WHERE id = ?", String.class, id);
    }
}
