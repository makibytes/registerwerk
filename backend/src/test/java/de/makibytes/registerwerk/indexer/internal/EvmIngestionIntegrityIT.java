package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.indexer.internal.GraphNodeClient.BlockMeta;
import de.makibytes.registerwerk.indexer.internal.GraphNodeClient.GraphTransfer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** P4B-6 / P4-06 against PostgreSQL: huge amounts index; a poisoned batch persists ERROR; unlinked rows are repaired. */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class EvmIngestionIntegrityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @MockitoBean GraphNodeClient graphNodeClient;
    @Autowired GraphNodeSyncService service;
    @Autowired ChainConfigRepository chains;
    @Autowired JdbcTemplate jdbc;

    private ChainConfig newChain() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, graph_node_url, enabled)
                VALUES (?, ?, 'Ingest IT', 'EVM', 'TESTNET', 'http://localhost:8545', 'http://graph', true)
                """, id, "ETHEREUM_ING_" + id.toString().substring(0, 8));
        return chains.findById(id).orElseThrow();
    }

    private void feed(ChainConfig chain, String amount, String token) {
        when(graphNodeClient.fetchMeta(any(), any())).thenReturn(Optional.of(new BlockMeta(100, "0xb100", false)));
        when(graphNodeClient.fetchTransfers(any(), anyLong(), anyInt(), anyInt())).thenReturn(List.of(
                new GraphTransfer("id-" + UUID.randomUUID(), token, "0x0000000000000000000000000000000000000000",
                        "0xto", null, amount, "MINT", 100L, 1_700_000_000L, "0xtx" + UUID.randomUUID(), 0L)));
    }

    @Test
    @DisplayName("a 2^256-1 amount is indexed; the deployment-less row is linked once the deployment exists")
    void hugeAmountIndexedAndUnlinkedRowRepaired() {
        ChainConfig chain = newChain();
        String token = "0xToken" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        feed(chain, new BigInteger("2").pow(256).subtract(BigInteger.ONE).toString(), token);

        service.syncChain(chain);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM token_transfer WHERE chain_config_id = ?", Integer.class, chain.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deployment_id FROM token_transfer WHERE chain_config_id = ?", UUID.class, chain.getId())).isNull();

        UUID issuer = UUID.randomUUID(), asset = UUID.randomUUID(), dep = UUID.randomUUID();
        jdbc.update("INSERT INTO legal_entity (id, entity_number, type, current_name) VALUES (?, ?, 'ISSUER', 'I')", issuer, "ISS-" + issuer.toString().substring(0, 8));
        jdbc.update("INSERT INTO asset (id, asset_number, issuer_id, name, token_standard, status) VALUES (?, ?, ?, 'A', 'ERC20', 'ISSUED')", asset, "AST-" + asset.toString().substring(0, 8), issuer);
        jdbc.update("INSERT INTO asset_deployment (id, asset_id, chain, network, contract_address, deployment_status, chain_config_id) VALUES (?, ?, 'ETHEREUM', 'TESTNET', ?, 'CONFIRMED', ?)",
                dep, asset, token.toLowerCase(), chain.getId());

        service.syncChain(chain); // idempotent second pass runs the link-repair

        Map<String, Object> row = jdbc.queryForMap("SELECT deployment_id, asset_id FROM token_transfer WHERE chain_config_id = ?", chain.getId());
        assertThat(row.get("deployment_id")).isEqualTo(dep);
        assertThat(row.get("asset_id")).isEqualTo(asset);
    }

    @Test
    @DisplayName("a batch that overflows the column persists consecutive_errors and ERROR (failed transaction cannot lose it)")
    void poisonedBatchPersistsErrorState() {
        ChainConfig chain = newChain();
        feed(chain, "1" + "0".repeat(100), "0xpoison"); // beyond even NUMERIC(96,18)

        for (int i = 0; i < GraphNodeSyncService.MAX_CONSECUTIVE_ERRORS; i++) {
            service.syncChain(chain);
        }

        Map<String, Object> state = jdbc.queryForMap(
                "SELECT status, consecutive_errors, last_error FROM indexer_state WHERE chain_config_id = ? AND indexer_type = 'GRAPH_NODE'", chain.getId());
        assertThat(state.get("status")).isEqualTo("ERROR");
        assertThat(state.get("consecutive_errors")).isEqualTo(GraphNodeSyncService.MAX_CONSECUTIVE_ERRORS);
        assertThat((String) state.get("last_error")).containsIgnoringCase("numeric field overflow");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM token_transfer WHERE chain_config_id = ?", Integer.class, chain.getId())).isZero();
    }
}
