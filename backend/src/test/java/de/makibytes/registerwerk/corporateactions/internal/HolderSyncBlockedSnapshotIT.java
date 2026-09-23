package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.indexer.api.HolderDataService;
import de.makibytes.registerwerk.indexer.api.UnmappedHolderIdentityException;
import de.makibytes.registerwerk.indexer.internal.NomineePoolHolderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T2-18 end to end against the real schema: pledging units into a lending-market contract with no
 * nominee-pool row persists a BLOCKED register and the record-date snapshot is refused visibly
 * (SNAPSHOT_BLOCKED); once the pool is registered the sync reconciles and the pool's entitlement
 * is snapshotted as HELD_LOOK_THROUGH, outside the payable total.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("T2-18 — blocked holder sync gates the corporate-action snapshot")
class HolderSyncBlockedSnapshotIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Autowired HolderDataService holderDataService;
    @Autowired NomineePoolHolderService nomineePoolHolderService;
    @Autowired CorporateActionService corporateActionService;
    @Autowired JdbcTemplate jdbc;

    private static String randomEvmAddress() {
        String hex = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
        return "0x" + hex.substring(0, 40);
    }

    @Test
    void pledgeIntoUnregisteredPool_blocksRegisterAndSnapshot_untilPoolRegistered() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID issuerId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        UUID operatorEntityId = UUID.randomUUID();
        UUID chainConfigId = UUID.randomUUID();
        UUID caId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String contract = randomEvmAddress();
        String investorWallet = randomEvmAddress();
        String marketAddress = randomEvmAddress();

        jdbc.update("INSERT INTO legal_entity (id, entity_number, type, current_name) VALUES (?, ?, 'ISSUER', 'T2-18 Issuer')",
                issuerId, "ISS-" + suffix);
        jdbc.update("INSERT INTO legal_entity (id, entity_number, type, current_name) VALUES (?, ?, 'INVESTOR', 'T2-18 Investor')",
                investorId, "INV-" + suffix);
        jdbc.update("INSERT INTO legal_entity (id, entity_number, type, current_name) VALUES (?, ?, 'INVESTOR', 'T2-18 Operator')",
                operatorEntityId, "OPR-" + suffix);
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, enabled)
                VALUES (?, ?, 'T2-18 Chain', 'EVM', 'TESTNET', 'http://localhost:8545', true)
                """, chainConfigId, "t218-" + chainConfigId);
        jdbc.update("""
                INSERT INTO asset (id, asset_number, issuer_id, name, token_standard, status)
                VALUES (?, ?, ?, 'T2-18 Bond', 'ERC20', 'ISSUED')
                """, assetId, "AST-" + suffix, issuerId);
        jdbc.update("""
                INSERT INTO asset_deployment (id, asset_id, chain, network, contract_address, deployment_status)
                VALUES (?, ?, 'ETHEREUM', 'TESTNET', ?, 'CONFIRMED')
                """, deploymentId, assetId, contract);
        jdbc.update("INSERT INTO asset_holder (asset_id, investor_id, wallet_address, nominal_amount) VALUES (?, ?, ?, 100)",
                assetId, investorId, investorWallet);
        insertTransfer(assetId, deploymentId, chainConfigId, contract,
                "0x0000000000000000000000000000000000000000", investorWallet, "100", "MINT");
        // Borrower pledges 40 units into the lending market: the market contract now holds them.
        insertTransfer(assetId, deploymentId, chainConfigId, contract, investorWallet, marketAddress, "40", "TRANSFER");

        jdbc.update("""
                INSERT INTO corporate_action (id, asset_id, action_type, status, record_date, payment_date,
                                              amount_per_unit, currency, initiated_by)
                VALUES (?, ?, 'COUPON', 'ANNOUNCED', ?, ?, 0.05, 'EUR', ?)
                """, caId, assetId, LocalDate.now(), LocalDate.now().plusDays(10), UUID.randomUUID());

        // 1. No nominee-pool row for the market → the register is BLOCKED, persisted, not just logged.
        assertThatThrownBy(() -> holderDataService.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(UnmappedHolderIdentityException.class);
        Map<String, Object> asset = jdbc.queryForMap(
                "SELECT holder_sync_status, holder_sync_unmapped_wallets, last_successful_holder_sync_at FROM asset WHERE id = ?",
                assetId);
        assertThat(asset.get("holder_sync_status")).isEqualTo("BLOCKED");
        assertThat((String) asset.get("holder_sync_unmapped_wallets")).isEqualTo(marketAddress.toLowerCase());
        assertThat(asset.get("last_successful_holder_sync_at")).isNull();

        // 2. The record-date snapshot is refused visibly instead of reading the stale register.
        corporateActionService.processDailyTransitions();
        Map<String, Object> ca = jdbc.queryForMap("SELECT status, snapshot_blocked_reason FROM corporate_action WHERE id = ?", caId);
        assertThat(ca.get("status")).isEqualTo("SNAPSHOT_BLOCKED");
        assertThat((String) ca.get("snapshot_blocked_reason")).contains(marketAddress.toLowerCase());
        assertThat(entryCount(caId)).isZero();

        // 3. Register the pool address → sync reconciles, the pool row carries the pledged units.
        nomineePoolHolderService.register(assetId, marketAddress, NomineePoolHolderService.LENDING_MARKET,
                operatorEntityId, null, "REGISTRY_ADMIN");
        holderDataService.syncHoldersFromBlockchain(assetId);
        asset = jdbc.queryForMap(
                "SELECT holder_sync_status, holder_sync_unmapped_wallets, last_successful_holder_sync_at FROM asset WHERE id = ?",
                assetId);
        assertThat(asset.get("holder_sync_status")).isEqualTo("OK");
        assertThat(asset.get("holder_sync_unmapped_wallets")).isNull();
        assertThat(asset.get("last_successful_holder_sync_at")).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT nominal_amount FROM asset_holder WHERE asset_id = ? AND holder_kind = 'NOMINEE_POOL'",
                BigDecimal.class, assetId)).isEqualByComparingTo("40");

        // 4. The retried snapshot succeeds; the pool's entitlement is HELD and not in the payable total.
        corporateActionService.processDailyTransitions();
        ca = jdbc.queryForMap("SELECT status, snapshot_blocked_reason, total_amount FROM corporate_action WHERE id = ?", caId);
        assertThat(ca.get("status")).isEqualTo("COMPUTED");
        assertThat(ca.get("snapshot_blocked_reason")).isNull();
        assertThat((BigDecimal) ca.get("total_amount")).isEqualByComparingTo("3");
        List<Map<String, Object>> entries = jdbc.queryForList(
                "SELECT wallet_address, payout_status, entitlement_amount FROM corporate_action_entry "
                        + "WHERE corporate_action_id = ? ORDER BY payout_status", caId);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("payout_status")).isEqualTo("HELD_LOOK_THROUGH");
        assertThat((String) entries.get(0).get("wallet_address")).isEqualToIgnoringCase(marketAddress);
        assertThat((BigDecimal) entries.get(0).get("entitlement_amount")).isEqualByComparingTo("2");
        assertThat(entries.get(1).get("payout_status")).isEqualTo("PAYABLE");
        assertThat((BigDecimal) entries.get(1).get("entitlement_amount")).isEqualByComparingTo("3");
    }

    private void insertTransfer(UUID assetId, UUID deploymentId, UUID chainConfigId, String contract,
                                String from, String to, String amount, String type) {
        jdbc.update("""
                INSERT INTO token_transfer
                  (asset_id, deployment_id, chain_config_id, contract_address, from_address, to_address,
                   amount, event_type, tx_hash, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, assetId, deploymentId, chainConfigId, contract, from, to, new BigDecimal(amount), type,
                "0x" + UUID.randomUUID().toString().replace("-", ""));
    }

    private int entryCount(UUID caId) {
        return jdbc.queryForObject("SELECT count(*) FROM corporate_action_entry WHERE corporate_action_id = ?",
                Integer.class, caId);
    }
}
