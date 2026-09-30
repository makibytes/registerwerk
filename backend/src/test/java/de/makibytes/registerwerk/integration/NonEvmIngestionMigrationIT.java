package de.makibytes.registerwerk.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 4 K2: V19 relaxes the token_transfer keys and repairs what is safe to repair on existing data. */
@Testcontainers
@DisplayName("V19 non-EVM ingestion keys migration")
class NonEvmIngestionMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Test
    @DisplayName("V18 rejects the second row of a transaction; V19 accepts it, links Canton rows and rewinds Starknet")
    void keysRelaxedAndExistingRowsRepaired() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("18").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID issuer = UUID.randomUUID(), asset = UUID.randomUUID(), canton = UUID.randomUUID(), starknet = UUID.randomUUID(),
                dep = UUID.randomUUID();
        jdbc.update("INSERT INTO legal_entity (id, entity_number, type, current_name) VALUES (?, 'ISS-K2', 'ISSUER', 'I')", issuer);
        jdbc.update("INSERT INTO asset (id, asset_number, issuer_id, name, token_standard, status) VALUES (?, 'AST-K2', ?, 'A', 'CANTON_TOKEN', 'ISSUED')", asset, issuer);
        jdbc.update("INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url) VALUES (?, 'k2-canton', 'C', 'CANTON', 'TESTNET', 'http://x')", canton);
        jdbc.update("INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url) VALUES (?, 'k2-starknet', 'S', 'STARKNET', 'TESTNET', 'http://x')", starknet);
        jdbc.update("INSERT INTO asset_deployment (id, asset_id, chain, network, contract_address, deployment_status, chain_config_id) VALUES (?, ?, 'CANTON', 'TESTNET', 'INSTR-1', 'CONFIRMED', ?)", dep, asset, canton);
        jdbc.update("""
                INSERT INTO token_transfer (chain_config_id, contract_address, amount, event_type, tx_hash, log_index, occurred_at)
                VALUES (?, 'INSTR-1', 60, 'TRANSFER', 'upd-1', 0, '2026-01-01T00:00:00Z')""", canton);
        jdbc.update("INSERT INTO indexer_state (chain_config_id, indexer_type, last_synced_block) VALUES (?, 'STARKNET_POLL', 4242)", starknet);

        // Before V19: a second row of the same update/transaction (different log_index) is rejected by uq_transfer_solana.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO token_transfer (chain_config_id, contract_address, amount, event_type, tx_hash, log_index, occurred_at)
                VALUES (?, 'INSTR-1', 40, 'MINT', 'upd-1', 1, '2026-01-01T00:00:00Z')""", canton))
                .hasMessageContaining("duplicate key value violates unique constraint");

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        jdbc.update("""
                INSERT INTO token_transfer (chain_config_id, contract_address, amount, event_type, tx_hash, log_index, occurred_at)
                VALUES (?, 'INSTR-1', 40, 'MINT', 'upd-1', 1, '2026-01-01T00:00:00Z')""", canton);
        // Same position but a different emitting contract (Starknet: two tokens in one transaction) is distinct now ...
        jdbc.update("""
                INSERT INTO token_transfer (chain_config_id, contract_address, amount, event_type, tx_hash, log_index, occurred_at)
                VALUES (?, '0xaaa', 1, 'MINT', '0xtx', 0, '2026-01-01T00:00:00Z')""", starknet);
        jdbc.update("""
                INSERT INTO token_transfer (chain_config_id, contract_address, amount, event_type, tx_hash, log_index, occurred_at)
                VALUES (?, '0xbbb', 1, 'MINT', '0xtx', 0, '2026-01-01T00:00:00Z')""", starknet);
        // ... while a true duplicate is still rejected.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO token_transfer (chain_config_id, contract_address, amount, event_type, tx_hash, log_index, occurred_at)
                VALUES (?, '0xbbb', 1, 'MINT', '0xtx', 0, '2026-01-01T00:00:00Z')""", starknet))
                .hasMessageContaining("duplicate key value violates unique constraint");

        // Repairs: the pre-existing Canton row is linked to its deployment; the Starknet cursor is rewound.
        assertThat(jdbc.queryForObject("SELECT deployment_id FROM token_transfer WHERE tx_hash = 'upd-1' AND log_index = 0", UUID.class)).isEqualTo(dep);
        assertThat(jdbc.queryForObject("SELECT asset_id FROM token_transfer WHERE tx_hash = 'upd-1' AND log_index = 0", UUID.class)).isEqualTo(asset);
        assertThat(jdbc.queryForObject("SELECT last_synced_block FROM indexer_state WHERE chain_config_id = ?", Long.class, starknet)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM indexer_deployment_cursor", Integer.class)).isZero();
    }
}
