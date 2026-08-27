package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the confirm-on-reconfirmation flow this job uses to avoid surfacing a false-positive
 * drift right after a fresh boot or restart, while a chain projection (Chaincache's durable event
 * stream, an indexer) is still catching up and token_transfer briefly lags the registry.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("ChainDriftDetectionJob — confirm-on-reconfirmation gate")
class ChainDriftDetectionJobIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Autowired ChainDriftDetectionJob job;
    @Autowired JdbcTemplate jdbc;

    private UUID assetId;
    private UUID deploymentId;
    private String wallet;

    @BeforeEach
    void setUp() {
        assetId = UUID.randomUUID();
        deploymentId = UUID.randomUUID();
        wallet = randomEvmAddress();
        String contractAddress = randomEvmAddress();
        UUID issuerId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        UUID chainConfigId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        jdbc.update("""
                INSERT INTO legal_entity (id, entity_number, type, current_name)
                VALUES (?, ?, 'ISSUER', 'Drift IT Issuer')
                """, issuerId, "ISS-" + suffix);
        jdbc.update("""
                INSERT INTO legal_entity (id, entity_number, type, current_name)
                VALUES (?, ?, 'INVESTOR', 'Drift IT Investor')
                """, investorId, "INV-" + suffix);
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, enabled)
                VALUES (?, ?, 'Drift IT Chain', 'EVM', 'TESTNET', 'http://localhost:8545', true)
                """, chainConfigId, "drift-it-" + chainConfigId);
        jdbc.update("""
                INSERT INTO asset (id, asset_number, issuer_id, name, token_standard, status)
                VALUES (?, ?, ?, 'Drift IT Asset', 'ERC20', 'ISSUED')
                """, assetId, "AST-" + suffix, issuerId);
        jdbc.update("""
                INSERT INTO asset_deployment (id, asset_id, chain, network, contract_address, deployment_status)
                VALUES (?, ?, 'ETHEREUM', 'TESTNET', ?, 'CONFIRMED')
                """, deploymentId, assetId, contractAddress);
        jdbc.update("""
                INSERT INTO asset_holder (asset_id, investor_id, wallet_address, nominal_amount)
                VALUES (?, ?, ?, 100)
                """, assetId, investorId, wallet);
        // Deliberately short of the registered 100 — simulates token_transfer lagging the
        // registry right after a fresh boot, while the chain projection is still catching up.
        insertTransfer(chainConfigId, contractAddress, "40");
    }

    private static String randomEvmAddress() {
        String hex = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
        return "0x" + hex.substring(0, 40);
    }

    private void insertTransfer(UUID chainConfigId, String contractAddress, String amount) {
        jdbc.update("""
                INSERT INTO token_transfer
                  (asset_id, deployment_id, chain_config_id, contract_address, from_address, to_address,
                   amount, event_type, tx_hash, occurred_at)
                VALUES (?, ?, ?, ?, '0x0000000000000000000000000000000000000000', ?, ?, 'MINT', ?, now())
                """, assetId, deploymentId, chainConfigId, contractAddress, wallet, new BigDecimal(amount),
                "0x" + UUID.randomUUID().toString().replace("-", ""));
    }

    private Map<String, Object> driftRow() {
        return jdbc.queryForMap("""
                SELECT status, confirmed, severity, resolved_by
                FROM chain_drift_event
                WHERE deployment_id = ? AND wallet_address = ?
                """, deploymentId, wallet);
    }

    private int driftRowCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM chain_drift_event WHERE deployment_id = ? AND wallet_address = ?
                """, Integer.class, deploymentId, wallet);
    }

    /** Scoped to this test's own (deploymentId, wallet) rather than a bare global count — the
     *  shared Testcontainers instance persists across test methods in this class, and only the
     *  transaction rollback (not a schema reset) separates them. */
    private long confirmedOpenCountForThisWallet() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM chain_drift_event
                WHERE status = 'OPEN' AND confirmed = true AND deployment_id = ? AND wallet_address = ?
                """, Long.class, deploymentId, wallet);
    }

    @Test
    @Transactional
    @DisplayName("a divergence seen once is an unconfirmed candidate, invisible to the confirmed-open gauge query")
    void firstSighting_isUnconfirmedCandidate() {
        job.checkDrift();

        Map<String, Object> row = driftRow();
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat((Boolean) row.get("confirmed")).isFalse();
        assertThat(confirmedOpenCountForThisWallet()).isZero();
    }

    @Test
    @Transactional
    @DisplayName("a divergence still present on a second run is promoted to a confirmed, real OPEN case")
    void secondSighting_confirmsTheCase() {
        job.checkDrift();
        job.checkDrift();

        Map<String, Object> row = driftRow();
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat((Boolean) row.get("confirmed")).isTrue();
        assertThat(row.get("severity")).isEqualTo("CRITICAL"); // 60/100 = 60% >= 1% threshold
        assertThat(confirmedOpenCountForThisWallet()).isEqualTo(1);
        assertThat(driftRowCount()).isEqualTo(1); // still one row — refreshed, not duplicated
    }

    @Test
    @Transactional
    @DisplayName("an unconfirmed candidate that stops diverging before confirmation is auto-resolved, not left OPEN")
    void candidateSelfHeals_beforeConfirmation() {
        job.checkDrift(); // candidate created, still short by 60

        // The projection "catches up": the missing 60 arrives before the next scan.
        jdbc.update("""
                INSERT INTO token_transfer
                  (asset_id, deployment_id, chain_config_id, contract_address, from_address, to_address,
                   amount, event_type, tx_hash, occurred_at)
                SELECT asset_id, deployment_id, chain_config_id, contract_address,
                       '0x0000000000000000000000000000000000000000', to_address, 60, 'MINT',
                       ?, now()
                FROM token_transfer WHERE deployment_id = ? LIMIT 1
                """, "0x" + UUID.randomUUID().toString().replace("-", ""), deploymentId);

        job.checkDrift();

        Map<String, Object> row = driftRow();
        assertThat(row.get("status")).isEqualTo("RESOLVED");
        assertThat((Boolean) row.get("confirmed")).isFalse();
        assertThat(row.get("resolved_by")).isNull(); // system auto-clear, not a human resolve()
    }

    @Test
    @Transactional
    @DisplayName("a CONFIRMED case is never auto-resolved by the job, even once balances agree again")
    void confirmedCase_isNeverAutoResolved() {
        job.checkDrift(); // candidate
        job.checkDrift(); // confirmed

        jdbc.update("""
                INSERT INTO token_transfer
                  (asset_id, deployment_id, chain_config_id, contract_address, from_address, to_address,
                   amount, event_type, tx_hash, occurred_at)
                SELECT asset_id, deployment_id, chain_config_id, contract_address,
                       '0x0000000000000000000000000000000000000000', to_address, 60, 'MINT',
                       ?, now()
                FROM token_transfer WHERE deployment_id = ? LIMIT 1
                """, "0x" + UUID.randomUUID().toString().replace("-", ""), deploymentId);

        job.checkDrift(); // balances now agree, but this case was already handed to a human

        Map<String, Object> row = driftRow();
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat((Boolean) row.get("confirmed")).isTrue();
    }
}
