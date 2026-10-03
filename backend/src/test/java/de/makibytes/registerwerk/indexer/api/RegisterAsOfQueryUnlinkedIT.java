package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.config.TestSecurityConfig;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 0b H7 against the real schema and the real JPQL: transfers whose {@code deployment_id} is still NULL are
 * attributed to the asset by asset id or by (chain, contract) and counted in the record-date positions instead of
 * dropping out of the snapshot silently.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("H7 - RegisterAsOfQuery counts transfers that are not yet linked to a deployment")
class RegisterAsOfQueryUnlinkedIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Autowired RegisterAsOfQuery query;
    @Autowired JdbcTemplate jdbc;

    private static String randomEvmAddress() {
        String hex = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
        return "0x" + hex.substring(0, 40);
    }

    private static final String ZERO = "0x0000000000000000000000000000000000000000";

    private void transfer(UUID assetId, UUID deploymentId, UUID chainId, String contract, String from, String to,
                          String amount, String finality) {
        jdbc.update("""
                INSERT INTO token_transfer
                  (asset_id, deployment_id, chain_config_id, contract_address, from_address, to_address,
                   amount, event_type, tx_hash, occurred_at, finality_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TRANSFER', ?, now() - interval '3 days', ?)
                """, assetId, deploymentId, chainId, contract, from, to, new BigDecimal(amount),
                "0x" + UUID.randomUUID().toString().replace("-", ""), finality);
    }

    @Test
    void unlinkedTransfersAreAttributedByAssetIdAndByContract() {
        UUID assetId = UUID.randomUUID();
        UUID otherAssetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID chainId = UUID.randomUUID();
        UUID issuerId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String contract = randomEvmAddress();
        String otherContract = randomEvmAddress();
        String a = "0x00000000000000000000000000000000000000a1";
        String b = "0x00000000000000000000000000000000000000b2";
        String c = "0x00000000000000000000000000000000000000c3";

        jdbc.update("INSERT INTO legal_entity (id, entity_number, type, current_name) VALUES (?, ?, 'ISSUER', 'H7 Issuer')",
                issuerId, "ISS-" + suffix);
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, enabled)
                VALUES (?, ?, 'H7 Chain', 'EVM', 'TESTNET', 'http://localhost:8545', true)
                """, chainId, "h7-" + chainId);
        jdbc.update("""
                INSERT INTO asset (id, asset_number, issuer_id, name, token_standard, status)
                VALUES (?, ?, ?, 'H7 Bond', 'ERC20', 'ISSUED')
                """, assetId, "AST-" + suffix, issuerId);
        jdbc.update("""
                INSERT INTO asset (id, asset_number, issuer_id, name, token_standard, status)
                VALUES (?, ?, ?, 'H7 Other', 'ERC20', 'ISSUED')
                """, otherAssetId, "AST-O" + suffix, issuerId);
        jdbc.update("""
                INSERT INTO asset_deployment (id, asset_id, chain, network, chain_config_id, contract_address,
                                              deployment_status, token_decimals)
                VALUES (?, ?, 'ETHEREUM', 'TESTNET', ?, ?, 'CONFIRMED', 0)
                """, deploymentId, assetId, chainId, contract);

        // linked mint of 100 to A
        transfer(assetId, deploymentId, chainId, contract, ZERO, a, "100", "FINALIZED");
        // NOT linked to the deployment, but the asset id says whose it is: A -> B 30
        transfer(assetId, null, chainId, contract, a, b, "30", "FINALIZED");
        // NOT linked, no asset id either, but (chain, contract) is this asset's deployment: B -> C 10 (contract case-insensitive)
        transfer(null, null, chainId, contract.toUpperCase().replace("0X", "0x"), b, c, "10", "FINALIZED");
        // another contract: must not count
        transfer(null, null, chainId, otherContract, ZERO, a, "999", "FINALIZED");
        // another asset's unlinked row on the same contract address: must not count
        transfer(otherAssetId, null, chainId, contract, ZERO, a, "888", "FINALIZED");
        // not final yet: blocks the snapshot, does not move a balance
        transfer(null, null, chainId, contract, ZERO, c, "5", "PROVISIONAL");

        RegisterAsOfQuery.AsOfBalances result = query.balancesAsOf(assetId, Instant.now());

        assertThat(result.unsupportedReason()).isNull();
        assertThat(result.balances().get(a)).isEqualByComparingTo("70");
        assertThat(result.balances().get(b)).isEqualByComparingTo("20");
        assertThat(result.balances().get(c)).isEqualByComparingTo("10");
        assertThat(result.unlinkedAttributed()).isEqualTo(2);
        assertThat(result.unfinalizedBeforeCutoff()).isEqualTo(1);
    }
}
