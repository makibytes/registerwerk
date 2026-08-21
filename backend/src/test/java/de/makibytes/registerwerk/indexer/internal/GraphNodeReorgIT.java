package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link ReorgGuard}'s fork-detection algorithm against a REAL chain reorg — via Anvil's
 * {@code anvil_reorg} RPC, not a mocked hash mismatch — rather than only the algorithm-level
 * coverage {@code GraphNodeSyncServiceTest} already has with a stubbed probe.
 *
 * <p>Scoped deliberately: this exercises {@link ReorgGuard} directly with a hand-built
 * {@link ReorgGuard.FinalityProbe} that reads block hashes straight from Anvil over RPC, not
 * {@link GraphNodeSyncService#probeEvmBlock} itself (which reads the hash from a graph-node
 * {@code _meta} GraphQL query — standing up a real graph-node instance in a test is impractical).
 * {@code ReorgGuard} is the actual reusable fork-detection state machine both
 * {@code GraphNodeSyncService} (EVM) and {@code StarknetTransferSyncService} depend on, so this
 * is the right unit to prove against a genuinely forked chain: "does a real reorg get detected
 * and correctly orphan the right rows," independent of which chain-specific probe supplies the
 * hash.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("ReorgGuard — real anvil_reorg fork detection")
class GraphNodeReorgIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    // Matches docker-compose.yml's anvil service: entrypoint anvil, --host 0.0.0.0 so the
    // container's own network interface (not just its loopback) accepts connections, which is
    // what lets Testcontainers reach it via the mapped host port.
    @Container
    static GenericContainer<?> anvil = new GenericContainer<>("ghcr.io/foundry-rs/foundry:v1.7.1")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("anvil"))
            .withCommand("--host", "0.0.0.0", "--chain-id", "11155111")
            .withExposedPorts(8545)
            .waitingFor(Wait.forLogMessage(".*Listening on.*\\n", 1));

    @Autowired
    private ReorgGuard reorgGuard;

    @Autowired
    private TokenTransferRepository tokenTransferRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private String rpcUrl() {
        return "http://" + anvil.getHost() + ":" + anvil.getMappedPort(8545);
    }

    /** Raw JSON-RPC call — anvil_reorg/anvil_mine are Anvil-specific methods web3j has no typed
     *  binding for; this is the same shape verified by hand against a live container before
     *  writing this test (see the session notes this test's commit accompanies). */
    private String rpc(String method, String paramsJson) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + paramsJson + ",\"id\":1}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rpcUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private long blockNumber() throws Exception {
        String result = rpc("eth_blockNumber", "[]");
        return Long.decode(extractResult(result));
    }

    private String blockHash(long number) throws Exception {
        String result = rpc("eth_getBlockByNumber", "[\"0x" + Long.toHexString(number) + "\", false]");
        // Minimal hand-rolled extraction (no JSON library pulled in for one field) — "hash":"0x..".
        int idx = result.indexOf("\"hash\":\"");
        int start = idx + 8;
        int end = result.indexOf('"', start);
        return result.substring(start, end);
    }

    private static String extractResult(String jsonRpcResponse) {
        int idx = jsonRpcResponse.indexOf("\"result\":\"");
        int start = idx + 10;
        int end = jsonRpcResponse.indexOf('"', start);
        return jsonRpcResponse.substring(start, end);
    }

    /** Mirrors GraphNodeSyncService#probeEvmBlock's decision logic exactly (fresh-hash-vs-stored
     *  comparison, then confirmation-depth check), just sourcing the fresh hash directly from
     *  Anvil's RPC instead of a graph-node _meta query. */
    private ReorgGuard.ProbeOutcome probe(UUID chainConfigId, long requiredConfirmations, long headBlock, long blockNumber) throws Exception {
        String freshHash = blockHash(blockNumber);
        List<String> stored = tokenTransferRepository.findDistinctBlockHashesAt(chainConfigId, blockNumber);
        if (!stored.isEmpty() && stored.stream().noneMatch(freshHash::equals)) {
            return new ReorgGuard.ProbeOutcome(ReorgGuard.ProbeResult.ORPHANED, freshHash);
        }
        long depth = headBlock - blockNumber + 1;
        return new ReorgGuard.ProbeOutcome(
                depth >= requiredConfirmations ? ReorgGuard.ProbeResult.FINALIZED : ReorgGuard.ProbeResult.PROVISIONAL,
                freshHash);
    }

    private UUID seedChainConfig() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, enabled)
                VALUES (?, 'reorg-it-chain', 'Reorg IT Chain', 'EVM', 'TESTNET', ?, true)
                """, id, rpcUrl());
        return id;
    }

    private void seedProvisionalTransfer(UUID chainConfigId, long blockNumber, String blockHash) {
        // No setId(): @GeneratedValue(strategy = UUID) means Hibernate assigns it on persist —
        // pre-assigning one makes Spring Data JPA treat this as an existing row and merge()
        // instead of persist(), which fails (no such row to merge into).
        TokenTransfer transfer = new TokenTransfer();
        transfer.setChainConfigId(chainConfigId);
        transfer.setContractAddress("0xReorgItContract");
        transfer.setFromAddress("0x0000000000000000000000000000000000000000");
        transfer.setToAddress("0x1111111111111111111111111111111111111111");
        transfer.setEventType(TokenTransfer.EventType.TRANSFER);
        transfer.setTxHash("0xreorg-it-tx-" + blockNumber);
        transfer.setBlockNumber(blockNumber);
        transfer.setBlockHash(blockHash);
        transfer.setOccurredAt(Instant.now());
        transfer.setFinalityStatus(FinalityLevel.PROVISIONAL);
        tokenTransferRepository.saveAndFlush(transfer);
    }

    @Test
    @DisplayName("a real anvil_reorg orphans the affected row and reverifyUnsettledWindow reports the fork block")
    void realReorgIsDetectedAndOrphansTheRow() throws Exception {
        UUID chainConfigId = seedChainConfig();

        // Mine to a small, known chain height.
        rpc("anvil_mine", "[\"0x5\"]");
        long head = blockNumber();
        assertThat(head).isEqualTo(5);

        long targetBlock = 3;
        String hashBeforeReorg = blockHash(targetBlock);
        seedProvisionalTransfer(chainConfigId, targetBlock, hashBeforeReorg);

        // First pass, before any reorg: hash matches, depth (3) is well under the deliberately
        // high confirmation requirement (100) — row stays PROVISIONAL, no fork reported.
        long requiredConfirmations = 100;
        ReorgGuard.VerifyResult before = reorgGuard.reverifyUnsettledWindow(
                chainConfigId, bn -> {
                    try {
                        return probe(chainConfigId, requiredConfirmations, head, bn);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        assertThat(before.reorgDetected()).isFalse();
        assertThat(before.promotedSafe()).isZero();
        assertThat(before.promotedFinalized()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT finality_status FROM token_transfer WHERE chain_config_id = ? AND block_number = ?",
                String.class, chainConfigId, targetBlock))
                .isEqualTo("PROVISIONAL");

        // Real reorg: replace the last 3 blocks (3, 4, 5) with new ones — genuinely changes
        // block 3's hash, exactly as a live chain fork would, not a substituted test double.
        rpc("anvil_reorg", "[3, []]");
        long headAfter = blockNumber();
        String hashAfterReorg = blockHash(targetBlock);
        assertThat(headAfter).isEqualTo(5); // same height, different chain
        assertThat(hashAfterReorg).isNotEqualTo(hashBeforeReorg);

        // Second pass: the probe now sees a real hash mismatch at block 3 against the stored
        // baseline — ReorgGuard must report a fork at exactly that block.
        ReorgGuard.VerifyResult after = reorgGuard.reverifyUnsettledWindow(
                chainConfigId, bn -> {
                    try {
                        return probe(chainConfigId, requiredConfirmations, headAfter, bn);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        assertThat(after.reorgDetected()).isTrue();
        assertThat(after.forkBlock()).isEqualTo(targetBlock);
        assertThat(after.orphaned()).isEqualTo(1);

        // Never deleted — only marked ORPHANED, matching this repo's "audit trail, not erasure"
        // convention for a regulated register.
        assertThat(tokenTransferRepository.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT finality_status FROM token_transfer WHERE chain_config_id = ? AND block_number = ?",
                String.class, chainConfigId, targetBlock))
                .isEqualTo("ORPHANED");
    }
}
