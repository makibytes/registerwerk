package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.MigratedDb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4B-6: raw uint256 token amounts (indexed base units) must fit every column that carries or sums them. The
 * baseline schema declares them NUMERIC(96,18); this guards the whole list, including the trading and repo
 * quantities, and the trigger / generated column that depend on the register balance columns.
 */
@Testcontainers
@DisplayName("Register and trading token-amount columns hold a full uint256")
class RegisterAmountColumnsIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Test
    @DisplayName("uint256 fits, the position-history trigger still fires and the generated drift delta is exact")
    void widensWithTriggerAndGeneratedColumn() {
        JdbcTemplate jdbc = MigratedDb.migrate(postgres).jdbc();

        UUID issuer = UUID.randomUUID(), investor = UUID.randomUUID(), asset = UUID.randomUUID(), chain = UUID.randomUUID();
        jdbc.update("INSERT INTO legal_entity (id, entity_number, type, current_name) VALUES (?, 'ISS-W', 'ISSUER', 'I')", issuer);
        jdbc.update("INSERT INTO legal_entity (id, entity_number, type, current_name) VALUES (?, 'INV-W', 'INVESTOR', 'V')", investor);
        jdbc.update("INSERT INTO asset (id, asset_number, issuer_id, name, token_standard, status) VALUES (?, 'AST-W', ?, 'A', 'ERC20', 'ISSUED')", asset, issuer);
        jdbc.update("INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url) VALUES (?, 'w-chain', 'W', 'EVM', 'TESTNET', 'http://x')", chain);
        jdbc.update("INSERT INTO asset_holder (asset_id, investor_id, wallet_address, nominal_amount) VALUES (?, ?, '0xabc', 12.5)", asset, investor);
        jdbc.update("""
                INSERT INTO token_transfer (asset_id, chain_config_id, contract_address, amount, event_type, tx_hash, occurred_at)
                VALUES (?, ?, '0xc', 7.25, 'MINT', '0xt1', now())""", asset, chain);
        jdbc.update("INSERT INTO chain_drift_event (asset_id, deployment_id, wallet_address, db_balance, onchain_balance, severity) VALUES (?, ?, '0xabc', 1, 4, 'WARNING')",
                asset, UUID.randomUUID());

        BigDecimal huge = new BigDecimal(BigInteger.TWO.pow(256).subtract(BigInteger.ONE));

        assertThat(jdbc.queryForObject("SELECT delta FROM chain_drift_event", BigDecimal.class)).isEqualByComparingTo("3");
        for (String col : new String[] {"asset_holder.nominal_amount", "token_transfer.amount", "chain_drift_event.delta",
                "chain_drift_event.db_balance", "chain_drift_event.onchain_balance",
                "asset_holder_position_history.nominal_amount",
                "corporate_action_entry.nominal_at_record", "corporate_action_entry.entitlement_amount",
                "corporate_action.total_amount", "corporate_action.rounding_residual", "register_statement.nominal_amount",
                "asset_redemption_burn.amount",
                // trading and repo quantities are token amounts too
                "trade_listing.quantity_total", "trade_listing.quantity_available",
                "trade_execution.requested_quantity", "trade_execution.executed_quantity",
                "repo_rfq.collateral_quantity", "repo_trade.collateral_quantity",
                "repo_trade.pending_substitution_quantity", "repo_substitution_request.quantity",
                "repo_lifecycle_event.quantity"}) {
            String[] p = col.split("\\.");
            assertThat(jdbc.queryForObject("SELECT numeric_precision FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                    Integer.class, p[0], p[1])).as(col).isEqualTo(96);
        }

        int before = jdbc.queryForObject("SELECT count(*) FROM asset_holder_position_history", Integer.class);
        jdbc.update("UPDATE asset_holder SET nominal_amount = ?", huge); // the history trigger must still fire
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_holder_position_history", Integer.class)).isEqualTo(before + 1);
        jdbc.update("""
                INSERT INTO token_transfer (asset_id, chain_config_id, contract_address, amount, event_type, tx_hash, occurred_at)
                VALUES (?, ?, '0xc', ?, 'MINT', '0xt2', now())""", asset, chain, huge);
        assertThat(jdbc.queryForObject("SELECT kind FROM chain_drift_event", String.class)).isEqualTo("DRIFT");
    }
}
