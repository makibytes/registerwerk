package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.indexer.api.HolderDataService;
import de.makibytes.registerwerk.indexer.api.UnmappedHolderIdentityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 K1 against a real PostgreSQL with the coverage guard switched ON (the test profile
 * relaxes it): P4-01 (BLOCKED for unindexed deployments), P4-04 (negative net), P4-03 (drift per
 * chain / self-transfer / NOT_INDEXED), P4-07 (compensator on a BLOCKED register) and P4B-6
 * (a full uint256 amount flows through transfer, holder and history).
 */
@SpringBootTest(properties = "registerwerk.indexer.coverage.enforce=true")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Register coverage guard, negative balances and per-chain drift")
class RegisterCoverageIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    static final String ZERO = "0x0000000000000000000000000000000000000000";
    static final BigInteger UINT256_MAX = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);

    @Autowired JdbcTemplate jdbc;
    @Autowired HolderDataService holderDataService;
    @Autowired ChainDriftDetectionJob driftJob;
    @Autowired HolderRecomputeCompensator compensator;
    @Autowired PlatformTransactionManager transactionManager;

    // ── fixtures ────────────────────────────────────────────────────────────

    private UUID issuer() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO legal_entity (id, entity_number, type, current_name) VALUES (?, ?, 'ISSUER', 'Cov IT Issuer')",
                id, "ISS-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID investor() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO legal_entity (id, entity_number, type, current_name) VALUES (?, ?, 'INVESTOR', 'Cov IT Investor')",
                id, "INV-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID chain(String type, String graphNodeUrl) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, graph_node_url, enabled)
                VALUES (?, ?, 'Cov IT Chain', ?, 'TESTNET', 'http://localhost:8545', ?, true)
                """, id, "cov-it-" + id, type, graphNodeUrl);
        return id;
    }

    private void indexerState(UUID chainId, String type, String status, Instant syncedAt) {
        jdbc.update("""
                INSERT INTO indexer_state (chain_config_id, indexer_type, status, last_synced_at)
                VALUES (?, ?, ?, ?)
                """, chainId, type, status, java.sql.Timestamp.from(syncedAt));
    }

    private UUID asset() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO asset (id, asset_number, issuer_id, name, token_standard, status)
                VALUES (?, ?, ?, 'Cov IT Asset', 'ERC20', 'ISSUED')
                """, id, "AST-" + id.toString().substring(0, 8), issuer());
        return id;
    }

    private UUID deployment(UUID assetId, UUID chainId, String enumChain, String address) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO asset_deployment (id, asset_id, chain, network, contract_address, deployment_status, chain_config_id)
                VALUES (?, ?, ?, 'TESTNET', ?, 'CONFIRMED', ?)
                """, id, assetId, enumChain, address, chainId);
        return id;
    }

    private void holder(UUID assetId, String wallet, String nominal, boolean chainDerived) {
        jdbc.update("""
                INSERT INTO asset_holder (asset_id, investor_id, wallet_address, nominal_amount, chain_derived)
                VALUES (?, ?, ?, ?, ?)
                """, assetId, investor(), wallet, new BigDecimal(nominal), chainDerived);
    }

    private void transfer(UUID assetId, UUID deploymentId, UUID chainId, String contract,
                          String from, String to, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO token_transfer
                  (asset_id, deployment_id, chain_config_id, contract_address, from_address, to_address,
                   amount, event_type, tx_hash, occurred_at, finality_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TRANSFER', ?, now(), 'FINALIZED')
                """, assetId, deploymentId, chainId, contract, from, to, amount,
                "0x" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static String addr() {
        return "0x" + (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 40);
    }

    private Map<String, Object> assetSyncState(UUID assetId) {
        return jdbc.queryForMap("SELECT holder_sync_status, holder_sync_blocked_reason FROM asset WHERE id = ?", assetId);
    }

    private List<Map<String, Object>> driftEvents(UUID assetId) {
        return jdbc.queryForList("SELECT kind, severity, status, deployment_id FROM chain_drift_event WHERE asset_id = ?", assetId);
    }

    // ── P4-01 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P4-01: a Solana deployment with zero indexed rows is BLOCKED, not marked reconciled")
    void solanaWithoutTrackedMintBlocks() {
        UUID chainId = chain("SOLANA", null);
        indexerState(chainId, "SOLANA_POLL", "ACTIVE", Instant.now()); // poll alive, but no mint cursor
        UUID assetId = asset();
        deployment(assetId, chainId, "SOLANA", "So1anaMint" + UUID.randomUUID().toString().substring(0, 8));
        holder(assetId, "SolWalletManual" + UUID.randomUUID().toString().substring(0, 6), "500", false);

        assertThatThrownBy(() -> holderDataService.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(UnmappedHolderIdentityException.class)
                .hasMessageContaining("is not indexed");

        assertThat(assetSyncState(assetId)).containsEntry("holder_sync_status", "BLOCKED");
        assertThat((String) assetSyncState(assetId).get("holder_sync_blocked_reason")).contains("plain SPL transfers not observable");
    }

    @Test
    @DisplayName("P4-01: an EVM deployment on a chain without a Graph Node is BLOCKED")
    void evmWithoutGraphNodeBlocks() {
        UUID chainId = chain("EVM", null);
        UUID assetId = asset();
        deployment(assetId, chainId, "ETHEREUM", addr());

        assertThatThrownBy(() -> holderDataService.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(UnmappedHolderIdentityException.class);

        assertThat(assetSyncState(assetId)).containsEntry("holder_sync_status", "BLOCKED");
        assertThat((String) assetSyncState(assetId).get("holder_sync_blocked_reason")).contains("no Graph Node");
    }

    // ── P4-04 / P4B-6 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("P4-04: a negative net balance (transfer with no funding mint) BLOCKS instead of clamping to 0")
    void negativeNetBalanceBlocks() {
        UUID chainId = chain("EVM", "http://graph");
        indexerState(chainId, "GRAPH_NODE", "ACTIVE", Instant.now());
        UUID assetId = asset();
        String contract = addr();
        UUID dep = deployment(assetId, chainId, "ETHEREUM", contract);
        String a = addr();
        String b = addr();
        holder(assetId, a, "0", true);
        holder(assetId, b, "0", true);
        transfer(assetId, dep, chainId, contract, a, b, new BigDecimal("300"));

        assertThatThrownBy(() -> holderDataService.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(UnmappedHolderIdentityException.class)
                .hasMessageContaining("negative net balance");

        assertThat(assetSyncState(assetId)).containsEntry("holder_sync_status", "BLOCKED");
        assertThat(jdbc.queryForObject("SELECT nominal_amount FROM asset_holder WHERE asset_id = ? AND wallet_address = ?",
                BigDecimal.class, assetId, b)).isEqualByComparingTo("0"); // nothing written
    }

    @Test
    @DisplayName("P4B-6: a full uint256 mint flows through token_transfer, asset_holder and the V13 history trigger")
    void uint256AmountIsIndexedAndReconciled() {
        UUID chainId = chain("EVM", "http://graph");
        indexerState(chainId, "GRAPH_NODE", "ACTIVE", Instant.now());
        UUID assetId = asset();
        String contract = addr();
        UUID dep = deployment(assetId, chainId, "ETHEREUM", contract);
        String w = addr();
        holder(assetId, w, "0", true);
        transfer(assetId, dep, chainId, contract, ZERO, w, new BigDecimal(UINT256_MAX));

        holderDataService.syncHoldersFromBlockchain(assetId);

        assertThat(jdbc.queryForObject("SELECT nominal_amount FROM asset_holder WHERE asset_id = ? AND wallet_address = ?",
                BigDecimal.class, assetId, w)).isEqualByComparingTo(new BigDecimal(UINT256_MAX));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM asset_holder_position_history WHERE asset_id = ? AND nominal_amount = ?
                """, Integer.class, assetId, new BigDecimal(UINT256_MAX))).isEqualTo(1);
        assertThat(assetSyncState(assetId)).containsEntry("holder_sync_status", "OK");
    }

    // ── P4-07 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P4-07: the compensator reports Compensated for a BLOCKED register and leaves the outer transaction usable")
    void compensatorOnBlockedRegister() {
        UUID chainId = chain("EVM", null);
        UUID assetId = asset();
        deployment(assetId, chainId, "ETHEREUM", addr());
        ChainEffectRecord effect = new ChainEffectRecord(UUID.randomUUID(), chainId, 100L, "0xhash", null, null,
                "indexer", "HOLDER_BALANCE_SYNCED", "Asset", assetId, null, CompensationCategory.RECOMPUTE,
                null, null, null, null, "COMPENSATING", 1, Instant.now());

        Object[] result = new TransactionTemplate(transactionManager).execute(status -> {
            CompensationOutcome outcome = compensator.compensate(effect);
            return new Object[] {outcome, status.isRollbackOnly()};
        });

        assertThat(result[0]).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(result[1]).isEqualTo(false);
        assertThat(assetSyncState(assetId)).containsEntry("holder_sync_status", "BLOCKED"); // persisted
    }

    // ── P4-03 / P4-01 (drift job) ───────────────────────────────────────────

    @Test
    @DisplayName("P4-03: the same contract address on another chain no longer masks a missing history")
    void driftJobFiltersByChain() {
        String contract = addr();
        String wallet = addr();
        UUID chainA = chain("EVM", "http://graph");
        UUID chainB = chain("EVM", "http://graph");
        UUID assetX = asset();
        UUID assetY = asset();
        UUID depX = deployment(assetX, chainA, "ETHEREUM", contract);
        UUID depY = deployment(assetY, chainB, "POLYGON", contract);
        // Asset X really has the mint on chain A; asset Y (same address, chain B) has none.
        transfer(assetX, depX, chainA, contract, ZERO, wallet, new BigDecimal("100"));
        holder(assetX, wallet, "100", true);
        holder(assetY, wallet, "100", true);

        driftJob.checkDrift();

        assertThat(driftEvents(assetX)).isEmpty();
        List<Map<String, Object>> y = driftEvents(assetY);
        assertThat(y).hasSize(1);
        assertThat(y.get(0)).containsEntry("kind", "NOT_INDEXED").containsEntry("severity", "CRITICAL");
        assertThat(y.get(0).get("deployment_id")).isEqualTo(depY);
    }

    @Test
    @DisplayName("P4-03: drift is aggregated per (asset, wallet) across deployments; a self-transfer nets to zero")
    void driftJobAggregatesAcrossDeploymentsAndNetsSelfTransfers() {
        String wallet = addr();
        String contractA = addr();
        String contractB = addr();
        UUID chainA = chain("EVM", "http://graph");
        UUID chainB = chain("EVM", "http://graph");
        UUID assetId = asset();
        UUID depA = deployment(assetId, chainA, "ETHEREUM", contractA);
        UUID depB = deployment(assetId, chainB, "POLYGON", contractB);
        transfer(assetId, depA, chainA, contractA, ZERO, wallet, new BigDecimal("60"));
        transfer(assetId, depB, chainB, contractB, ZERO, wallet, new BigDecimal("40"));
        transfer(assetId, depA, chainA, contractA, wallet, wallet, new BigDecimal("25")); // self-transfer
        holder(assetId, wallet, "100", true);

        driftJob.checkDrift();

        assertThat(driftEvents(assetId)).isEmpty(); // 60 + 40 - 0 == 100 (old code: 125, or per-deployment 60/40 vs 100)

        jdbc.update("UPDATE asset_holder SET nominal_amount = 90 WHERE asset_id = ? AND wallet_address = ?", assetId, wallet);
        driftJob.checkDrift();

        List<Map<String, Object>> events = driftEvents(assetId);
        assertThat(events).hasSize(1); // ONE event for the (asset, wallet), not one per deployment
        assertThat(events.get(0)).containsEntry("kind", "DRIFT");
    }
}
