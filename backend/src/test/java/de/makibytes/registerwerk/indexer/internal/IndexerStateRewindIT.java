package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Graph Node indexer cursor reorg rewind")
class IndexerStateRewindIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired IndexerStateRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    @DisplayName("replayed and out-of-order episodes only move both cursors backward")
    void rewindIsAtomicIdempotentAndMinWise() {
        UUID chainId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, enabled)
                VALUES (?, ?, 'Cursor Rewind IT', 'EVM', 'TESTNET', 'http://localhost:8545', true)
                """, chainId, "cursor-rewind-" + chainId);

        IndexerState state = new IndexerState();
        state.setChainConfigId(chainId);
        state.setIndexerType(IndexerState.IndexerType.GRAPH_NODE);
        state.setLastSyncedBlock(100L);
        state.setLastFinalBlock(90L);
        repository.saveAndFlush(state);

        assertThat(repository.rewindBlockCursor(
                chainId, IndexerState.IndexerType.GRAPH_NODE, 79L)).isEqualTo(1);
        assertCursors(chainId, 79L, 79L);

        // Duplicate delivery and a later/higher fork must not advance either cursor.
        repository.rewindBlockCursor(chainId, IndexerState.IndexerType.GRAPH_NODE, 79L);
        repository.rewindBlockCursor(chainId, IndexerState.IndexerType.GRAPH_NODE, 89L);
        assertCursors(chainId, 79L, 79L);

        // An older/deeper fork remains applicable and moves both boundaries farther back.
        repository.rewindBlockCursor(chainId, IndexerState.IndexerType.GRAPH_NODE, 39L);
        assertCursors(chainId, 39L, 39L);
    }

    private void assertCursors(UUID chainId, long synced, long finalized) {
        var cursors = jdbc.queryForMap("""
                SELECT last_synced_block, last_final_block
                FROM indexer_state
                WHERE chain_config_id = ? AND indexer_type = 'GRAPH_NODE'
                """, chainId);
        assertThat(((Number) cursors.get("last_synced_block")).longValue()).isEqualTo(synced);
        assertThat(((Number) cursors.get("last_final_block")).longValue()).isEqualTo(finalized);
    }
}
