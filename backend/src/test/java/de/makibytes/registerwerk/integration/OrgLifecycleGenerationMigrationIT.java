package de.makibytes.registerwerk.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DisplayName("V23/V24 org lifecycle-generation migration")
class OrgLifecycleGenerationMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    @DisplayName("legacy removed/revoked predecessors can migrate beside active replacements")
    void legacyLifecycleDuplicatesMigrateAndRemainLifoRepresentable() throws SQLException {
        flyway(MigrationVersion.fromVersion("22")).migrate();
        insertLegacyLifecycleDuplicates();

        flyway(null).migrate();

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertThat(count(connection,
                    "SELECT count(*) FROM org_member_wallet WHERE status IN ('PENDING','REMOVAL_PENDING')"))
                    .isEqualTo(2);
            assertThat(count(connection,
                    "SELECT count(*) FROM ecosystem_trusted_issuer "
                            + "WHERE status IN ('PENDING','REMOVAL_PENDING')"))
                    .isEqualTo(2);
            assertThat(count(connection,
                    "SELECT count(*) FROM permission_grant WHERE grant_type='ORG' "
                            + "AND status IN ('PENDING','REVOCATION_PENDING')"))
                    .isEqualTo(2);
            assertThat(count(connection,
                    "SELECT count(*) FROM permission_grant WHERE grant_type='ROLE' "
                            + "AND status IN ('PENDING','REVOCATION_PENDING')"))
                    .isEqualTo(2);

            assertAddSidePredicate(connection, "uq_org_member_wallet_live");
            assertAddSidePredicate(connection, "uq_ecosystem_trusted_issuer_live");
            assertAddSidePredicate(connection, "uq_permission_grant_live_org");
            assertAddSidePredicate(connection, "uq_permission_grant_live_role");
        }
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void insertLegacyLifecycleDuplicates() throws SQLException {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO chain_config
                        (id, identifier, display_name, chain_type, network_type, chain_id, rpc_url)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'lifo-fixture',
                            'LIFO fixture', 'EVM', 'TESTNET', 31337, 'http://localhost:8545')
                    """);
            statement.executeUpdate("""
                    INSERT INTO legal_entity (id, entity_number, type, status, current_name)
                    VALUES ('00000000-0000-0000-0000-000000000002', 'LIFO-1', 'ISSUER',
                            'ACTIVE', 'LIFO Fixture GmbH')
                    """);
            statement.executeUpdate("""
                    INSERT INTO org_registration
                        (id, legal_entity_id, chain_config_id, org_address, status, registered_tx)
                    VALUES ('00000000-0000-0000-0000-000000000003',
                            '00000000-0000-0000-0000-000000000002',
                            '00000000-0000-0000-0000-000000000001',
                            '0x0000000000000000000000000000000000000003', 'ACTIVE', '0xregister')
                    """);
            statement.executeUpdate("""
                    INSERT INTO permission_definition (id, code, permission_hash, name, status)
                    VALUES
                        ('00000000-0000-0000-0000-000000000004', 'fixture.org', '0xorg',
                         'Fixture org grant', 'ACTIVE'),
                        ('00000000-0000-0000-0000-000000000005', 'fixture.role', '0xrole',
                         'Fixture role grant', 'ACTIVE')
                    """);
            statement.executeUpdate("""
                    INSERT INTO org_member_wallet
                        (id, org_registration_id, chain_config_id, wallet_address, status, bound_tx, removed_at)
                    VALUES
                        ('00000000-0000-0000-0000-000000000010',
                         '00000000-0000-0000-0000-000000000003',
                         '00000000-0000-0000-0000-000000000001',
                         '0x0000000000000000000000000000000000000010', 'REMOVED', '0xoldbind', now()),
                        ('00000000-0000-0000-0000-000000000011',
                         '00000000-0000-0000-0000-000000000003',
                         '00000000-0000-0000-0000-000000000001',
                         '0x0000000000000000000000000000000000000010', 'ACTIVE', '0xnewbind', NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ecosystem_trusted_issuer
                        (id, chain_config_id, issuer_address, claim_topics, status, added_tx, removed_at)
                    VALUES
                        ('00000000-0000-0000-0000-000000000020',
                         '00000000-0000-0000-0000-000000000001',
                         '0x0000000000000000000000000000000000000020', ARRAY[1]::BIGINT[],
                         'REMOVED', '0xoldadd', now()),
                        ('00000000-0000-0000-0000-000000000021',
                         '00000000-0000-0000-0000-000000000001',
                         '0x0000000000000000000000000000000000000020', ARRAY[1]::BIGINT[],
                         'ACTIVE', '0xnewadd', NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO permission_grant
                        (id, permission_definition_id, org_registration_id, grant_type, role_code,
                         status, granted_tx, revoked_at)
                    VALUES
                        ('00000000-0000-0000-0000-000000000030',
                         '00000000-0000-0000-0000-000000000004',
                         '00000000-0000-0000-0000-000000000003', 'ORG', NULL,
                         'REVOKED', '0xoldorggrant', now()),
                        ('00000000-0000-0000-0000-000000000031',
                         '00000000-0000-0000-0000-000000000004',
                         '00000000-0000-0000-0000-000000000003', 'ORG', NULL,
                         'ACTIVE', '0xneworggrant', NULL),
                        ('00000000-0000-0000-0000-000000000032',
                         '00000000-0000-0000-0000-000000000005',
                         '00000000-0000-0000-0000-000000000003', 'ROLE', 'TRADER',
                         'REVOKED', '0xoldrolegrant', now()),
                        ('00000000-0000-0000-0000-000000000033',
                         '00000000-0000-0000-0000-000000000005',
                         '00000000-0000-0000-0000-000000000003', 'ROLE', 'TRADER',
                         'ACTIVE', '0xnewrolegrant', NULL)
                    """);
        }
    }

    private static long count(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void assertAddSidePredicate(java.sql.Connection connection, String indexName)
            throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_get_indexdef(indexrelid) "
                + "FROM pg_index WHERE indexrelid = ?::regclass")) {
            statement.setString(1, indexName);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1))
                        .contains("'PENDING'", "'ACTIVE'")
                        .doesNotContain("REVOCATION_PENDING", "REVOCATION_FAILED",
                                "REMOVAL_PENDING", "REMOVAL_FAILED");
            }
        }
    }
}
