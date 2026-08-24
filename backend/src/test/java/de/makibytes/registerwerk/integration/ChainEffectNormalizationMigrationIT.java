package de.makibytes.registerwerk.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Testcontainers
@DisplayName("V20 chain-effect identity normalization migration")
class ChainEffectNormalizationMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    @DisplayName("case-normalization collision fails before rewriting with an actionable diagnosis")
    void normalizationCollisionFailsExplicitly() throws Exception {
        flyway(MigrationVersion.fromVersion("19")).migrate();

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO chain_config
                        (id, identifier, display_name, chain_type, network_type, chain_id, rpc_url)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'normalization-fixture',
                            'Normalization fixture', 'EVM', 'TESTNET', 31337,
                            'http://localhost:8545')
                    """);
            statement.executeUpdate("""
                    INSERT INTO chain_effect
                        (id, chain_config_id, block_number, block_hash, tx_hash, log_index,
                         source_event_key, module_name, effect_type, entity_type, entity_id, category)
                    VALUES
                        ('00000000-0000-0000-0000-000000000010',
                         '00000000-0000-0000-0000-000000000001', 42, '0xAB', '0xCD', 0,
                         'legacy-upper', 'fixture', 'FIXTURE_EFFECT', 'Fixture',
                         '00000000-0000-0000-0000-000000000099', 'INVERSE_FLIP'),
                        ('00000000-0000-0000-0000-000000000011',
                         '00000000-0000-0000-0000-000000000001', 42, '0xab', '0xcd', 0,
                         'legacy-lower', 'fixture', 'FIXTURE_EFFECT', 'Fixture',
                         '00000000-0000-0000-0000-000000000099', 'INVERSE_FLIP')
                    """);
        }

        Throwable failure = catchThrowable(() -> flyway(MigrationVersion.fromVersion("20")).migrate());

        assertThat(failure).isNotNull();
        assertThat(rootCause(failure).getMessage())
                .contains("V20 cannot normalize chain_effect source identities without data loss")
                .contains("FIXTURE_EFFECT")
                .contains("remove only a proven duplicate");
    }

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target(target)
                .load();
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }
}
