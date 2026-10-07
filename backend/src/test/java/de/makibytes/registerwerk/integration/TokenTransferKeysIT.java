package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.MigratedDb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 K2: the token_transfer dedup keys distinguish the rows of one transaction by their position
 * (log_index) and the emitting contract; a true duplicate is still refused.
 */
@Testcontainers
@DisplayName("token_transfer uniqueness keys (non-EVM ingestion)")
class TokenTransferKeysIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Test
    @DisplayName("a second row of a transaction at another position, or from another contract, is accepted; a duplicate is not")
    void keysDistinguishPositionAndContract() {
        JdbcTemplate jdbc = MigratedDb.migrate(postgres).jdbc();
        UUID canton = UUID.randomUUID(), starknet = UUID.randomUUID();
        jdbc.update("INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url) VALUES (?, 'k2-canton', 'C', 'CANTON', 'TESTNET', 'http://x')", canton);
        jdbc.update("INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url) VALUES (?, 'k2-starknet', 'S', 'STARKNET', 'TESTNET', 'http://x')", starknet);

        String insert = """
                INSERT INTO token_transfer (chain_config_id, contract_address, amount, event_type, tx_hash, log_index, occurred_at)
                VALUES (?, ?, ?, 'MINT', ?, ?, '2026-01-01T00:00:00Z')""";
        // Canton / Solana style: one update id, several positions.
        jdbc.update(insert, canton, "INSTR-1", 60, "upd-1", 0);
        jdbc.update(insert, canton, "INSTR-1", 40, "upd-1", 1);
        // Starknet: two watched tokens emit at the same position of one transaction.
        jdbc.update(insert, starknet, "0xaaa", 1, "0xtx", 0);
        jdbc.update(insert, starknet, "0xbbb", 1, "0xtx", 0);

        assertThatThrownBy(() -> jdbc.update(insert, starknet, "0xbbb", 1, "0xtx", 0))
                .hasMessageContaining("duplicate key value violates unique constraint");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM token_transfer", Integer.class)).isEqualTo(4);
    }
}
