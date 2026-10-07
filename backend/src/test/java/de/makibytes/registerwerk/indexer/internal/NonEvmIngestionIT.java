package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Phase 4 K2 against PostgreSQL: the Solana ingester end to end (mint registration, paging past the
 * page size, several rows per signature under the new key, abort-without-side-effects on a failed
 * getTransaction) and the Starknet occurred_at repair. The services are constructed by hand so the
 * RPC transport is a {@link MockRestServiceServer}; repositories, transactions and constraints are real.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Non-EVM ingestion against PostgreSQL")
class NonEvmIngestionIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    private static final String RPC = "http://solana-it-rpc";
    private static final String FIXTURE_MINT = "MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Autowired JdbcTemplate jdbc;
    @Autowired ChainConfigRepository chains;
    @Autowired IndexerStateRepository indexerStates;
    @Autowired TokenTransferRepository transfers;
    @Autowired SolanaMintSyncCursorRepository mintCursors;
    @Autowired AssetDeploymentRepository deployments;
    @Autowired IndexerSyncSupport syncSupport;

    private final RestClient.Builder restBuilder = RestClient.builder();
    private MockRestServiceServer server;
    private SolanaTransferSyncService solana;
    private ChainConfig chain;
    private String mint;
    private UUID depId;
    private UUID assetId;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(restBuilder).ignoreExpectOrder(true).build();
        solana = new SolanaTransferSyncService(chains, indexerStates, transfers, mintCursors, deployments,
                syncSupport, new ExplorerUrlBuilder(), restBuilder);

        UUID chainId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, enabled)
                VALUES (?, ?, 'Solana IT', 'SOLANA', 'TESTNET', ?, false)
                """, chainId, "sol-it-" + chainId, RPC);
        chain = chains.findById(chainId).orElseThrow();
        mint = "Mint" + UUID.randomUUID().toString().replace("-", "");
        UUID issuer = UUID.randomUUID();
        assetId = UUID.randomUUID();
        depId = UUID.randomUUID();
        jdbc.update("INSERT INTO legal_entity (id, entity_number, type, current_name) VALUES (?, ?, 'ISSUER', 'I')", issuer, "ISS-" + issuer.toString().substring(0, 8));
        jdbc.update("INSERT INTO asset (id, asset_number, issuer_id, name, token_standard, status) VALUES (?, ?, ?, 'A', 'SPL', 'ISSUED')", assetId, "AST-" + assetId.toString().substring(0, 8), issuer);
        jdbc.update("INSERT INTO asset_deployment (id, asset_id, chain, network, contract_address, deployment_status, chain_config_id) VALUES (?, ?, 'SOLANA', 'TESTNET', ?, 'CONFIRMED', ?)",
                depId, assetId, mint, chainId);
    }

    private String fixture(String name) throws IOException {
        try (var in = NonEvmIngestionIT.class.getResourceAsStream("/fixtures/solana/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace(FIXTURE_MINT, mint);
        }
    }

    private void expectSignatures(String extraMatcher, String json) {
        var expect = server.expect(requestTo(RPC))
                .andExpect(content().string(containsString("getSignaturesForAddress")));
        if (extraMatcher != null) {
            expect = expect.andExpect(content().string(containsString(extraMatcher)));
        }
        expect.andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + json + "}", MediaType.APPLICATION_JSON));
    }

    private void expectTransaction(String signature, String resultJson) {
        server.expect(requestTo(RPC))
                .andExpect(content().string(containsString("getTransaction")))
                .andExpect(content().string(containsString("\"" + signature + "\"")))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + resultJson + "}", MediaType.APPLICATION_JSON));
    }

    private String cursor() {
        return jdbc.queryForObject("SELECT last_synced_signature FROM solana_mint_sync_cursor WHERE chain_config_id = ? AND mint_address = ?",
                String.class, chain.getId(), mint);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM token_transfer WHERE chain_config_id = ?", Integer.class, chain.getId());
    }

    @Test
    @DisplayName("a confirmed deployment's mint is registered (cursor seeded); a poll with nothing new stamps state and cursor liveness")
    void registersMintAndStampsStateOnEmptyPoll() {
        assertThat(solana.registerConfirmedMints(chain)).isEqualTo(1);
        assertThat(solana.registerConfirmedMints(chain)).isZero(); // idempotent
        assertThat(jdbc.queryForObject("SELECT last_synced_at IS NULL FROM solana_mint_sync_cursor WHERE chain_config_id = ?", Boolean.class, chain.getId())).isTrue();

        expectSignatures(null, "[]");
        solana.pollChain(chain);

        assertThat(jdbc.queryForObject("SELECT last_synced_at IS NOT NULL FROM solana_mint_sync_cursor WHERE chain_config_id = ?", Boolean.class, chain.getId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM indexer_state WHERE chain_config_id = ? AND indexer_type = 'SOLANA_POLL'", String.class, chain.getId())).isEqualTo("ACTIVE");
        assertThat(cursor()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("a batch transaction persists two rows for one signature; a backlog larger than the page is fully processed oldest first")
    void pagesBacklogAndPersistsSeveralRowsPerSignature() throws IOException {
        solana.setSignaturePageLimit(2);
        // newest first: page 1 = sigC, sigB (full page), page 2 (before sigB) = sigA (short page -> done)
        server.expect(requestTo(RPC)).andExpect(content().string(containsString("getSignaturesForAddress")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("\"before\""))))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":[
                          {"signature":"sigC","slot":310000150,"err":null,"blockTime":1767225900},
                          {"signature":"sigB","slot":310000100,"err":null,"blockTime":1767225800}]}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(RPC)).andExpect(content().string(containsString("getSignaturesForAddress")))
                .andExpect(content().string(containsString("\"before\":\"sigB\"")))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":[
                          {"signature":"sigA","slot":310000050,"err":null,"blockTime":1767225700}]}""", MediaType.APPLICATION_JSON));
        expectTransaction("sigA", fixture("batch-two-transfers-token2022.json"));   // two rows
        expectTransaction("sigB", fixture("mint-to.json"));                          // one row
        expectTransaction("sigC", fixture("token2022-transfer-fee.json"));           // two rows

        solana.registerConfirmedMints(chain);
        solana.pollChain(chain);

        assertThat(rows()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM token_transfer WHERE chain_config_id = ? AND tx_hash = 'sigA'", Integer.class, chain.getId())).isEqualTo(2);
        assertThat(cursor()).isEqualTo("sigC");
        Map<String, Object> row = jdbc.queryForMap("SELECT deployment_id, asset_id, block_number, slot, contract_address FROM token_transfer WHERE chain_config_id = ? AND tx_hash = 'sigB'", chain.getId());
        assertThat(row.get("deployment_id")).isEqualTo(depId);
        assertThat(row.get("asset_id")).isEqualTo(assetId);
        assertThat(row.get("block_number")).isEqualTo(310000100L);
        assertThat(row.get("contract_address")).isEqualTo(mint);
        server.verify();

        // Replaying the same poll (cursor rolled back) is idempotent: the dedup key includes the position.
        jdbc.update("UPDATE solana_mint_sync_cursor SET last_synced_signature = NULL WHERE chain_config_id = ?", chain.getId());
        server.reset();
        server.expect(requestTo(RPC)).andExpect(content().string(containsString("getSignaturesForAddress")))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":[
                          {"signature":"sigB","slot":310000100,"err":null,"blockTime":1767225800}]}""", MediaType.APPLICATION_JSON));
        expectTransaction("sigB", fixture("mint-to.json"));
        solana.pollChain(chain);
        assertThat(rows()).isEqualTo(5);
    }

    @Test
    @DisplayName("getTransaction returning null aborts the mint's pass: no rows, cursor unchanged, failure counted")
    void getTransactionFailureDoesNotAdvanceCursor() throws IOException {
        solana.registerConfirmedMints(chain);
        jdbc.update("UPDATE solana_mint_sync_cursor SET last_synced_signature = 'sigOld' WHERE chain_config_id = ?", chain.getId());
        expectSignatures("\"until\":\"sigOld\"", """
                [{"signature":"sigNew2","slot":2,"err":null,"blockTime":1767225800},
                 {"signature":"sigNew1","slot":1,"err":null,"blockTime":1767225700}]""");
        expectTransaction("sigNew1", fixture("mint-to.json"));
        expectTransaction("sigNew2", "null");

        solana.pollChain(chain);

        assertThat(rows()).isZero();
        assertThat(cursor()).isEqualTo("sigOld");
        Map<String, Object> state = jdbc.queryForMap("SELECT consecutive_errors, last_error FROM indexer_state WHERE chain_config_id = ? AND indexer_type = 'SOLANA_POLL'", chain.getId());
        assertThat(state.get("consecutive_errors")).isEqualTo(1);
        assertThat((String) state.get("last_error")).contains("getTransaction");
        server.verify();
    }

    @Test
    @DisplayName("Starknet occurred_at repair rewrites poll-time stamps from block timestamps, moves the row, and is idempotent")
    void starknetOccurredAtRepair() {
        UUID sn = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, enabled)
                VALUES (?, ?, 'Starknet IT', 'STARKNET', 'TESTNET', 'http://starknet-it-rpc', false)
                """, sn, "sn-it-" + sn);
        jdbc.update("""
                INSERT INTO token_transfer (chain_config_id, contract_address, amount, event_type, tx_hash, block_number, log_index, occurred_at, raw_data)
                VALUES (?, '0xabc', 5, 'MINT', '0xt1', 77, 0, '2030-05-05T00:00:00Z', '{"blockNumber":77,"logIndex":0}'::jsonb)""", sn);
        MockRestServiceServer snServer = MockRestServiceServer.bindTo(restBuilder).build();
        snServer.expect(requestTo("http://starknet-it-rpc")).andExpect(content().string(containsString("starknet_getBlockWithTxHashes")))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"ACCEPTED_ON_L1\",\"timestamp\":1767225600}}", MediaType.APPLICATION_JSON));
        StarknetOccurredAtRepair repair = new StarknetOccurredAtRepair(chains, jdbc, restBuilder, true);
        ChainConfig snChain = chains.findById(sn).orElseThrow();

        assertThat(repair.repair(snChain)).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT extract(epoch FROM occurred_at)::bigint FROM token_transfer WHERE chain_config_id = ?", Long.class, sn)).isEqualTo(1767225600L);
        assertThat(jdbc.queryForObject("SELECT raw_data->>'blockTimestamp' FROM token_transfer WHERE chain_config_id = ?", String.class, sn)).isEqualTo("1767225600");
        assertThat(repair.repair(snChain)).isZero(); // marker present: no second RPC call needed
        snServer.verify();
    }
}
