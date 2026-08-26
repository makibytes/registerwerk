package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Chain-effect incarnation identity and deterministic compensation order")
class ChainEffectOrderingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ChainEffectRepository repository;
    @Autowired private ChainEffectRecorderImpl recorder;

    @Test
    @Transactional
    @DisplayName("two effects inserted in one transaction compensate in reverse journal sequence")
    void sameTransactionHasDeterministicLifoOrder() {
        UUID chainId = seedChain();
        UUID first = insertEffect(chainId, 100L, "0xa1", "first");
        UUID second = insertEffect(chainId, 100L, "0xa1", "second");

        List<Instant> timestamps = jdbc.query(
                "SELECT recorded_at FROM chain_effect WHERE id IN (?, ?) ORDER BY id",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(), first, second);
        assertThat(timestamps).hasSize(2);
        assertThat(timestamps.get(0)).isEqualTo(timestamps.get(1));
        assertThat(repository.findIdsAtOrAfter(chainId, 100L)).containsExactly(second, first);
    }

    @Test
    @Transactional
    @DisplayName("exact non-hex identities are case-sensitive and finalization settles only one incarnation")
    void identityQueriesAreProtocolSafeAndIncarnationScoped() {
        UUID chainId = seedChain();
        UUID mixedCase = insertEffect(chainId, 200L, "CaseSensitiveABC", "mixed");
        UUID lowerCase = insertEffect(chainId, 200L, "casesensitiveABC", "lower");

        assertThat(repository.findIdsByBlockHashes(chainId, List.of("CaseSensitiveABC")))
                .containsExactly(mixedCase);
        assertThat(repository.findIdsByBlockHashes(chainId, List.of("casesensitiveABC")))
                .containsExactly(lowerCase);

        assertThat(repository.settleAtBlock(chainId, 200L, "CaseSensitiveABC")).isEqualTo(1);
        assertThat(status(mixedCase)).isEqualTo("SETTLED");
        assertThat(status(lowerCase)).isEqualTo("ACTIVE");
    }

    @Test
    @Transactional
    @DisplayName("a JSONB round trip preserves exact effect replay despite Java number-width changes")
    void persistedJsonNumberWidthDoesNotBreakIdempotency() {
        UUID chainId = seedChain();
        UUID assetId = UUID.randomUUID();
        ChainEffectDescriptor first = new ChainEffectDescriptor(
                chainId, 400L, "0xblock400", "0xtx400", null,
                "blockchain", "VAULT_DEPOSIT_CAP_CONFIRMED", "AssetVaultState", assetId, assetId,
                CompensationCategory.INVERSE_FLIP,
                Map.of("blockNumber", 399L, "depositCap", "100"),
                Map.of("blockNumber", 400L, "depositCap", "200"), null, null);

        UUID inserted = recorder.record(first);
        ChainEffect reloaded = repository.findById(inserted).orElseThrow();
        assertThat(reloaded.getAfterState().get("blockNumber")).isInstanceOf(Integer.class);

        assertThat(recorder.record(first)).isEqualTo(inserted);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chain_effect WHERE id = ?", Integer.class, inserted)).isOne();
    }

    private UUID seedChain() {
        UUID chainId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config
                  (id, identifier, display_name, chain_type, network_type, rpc_url)
                VALUES (?, ?, 'Chain effect ordering', 'EVM', 'TESTNET', 'http://localhost:8545')
                """, chainId, "effect-order-" + chainId);
        return chainId;
    }

    private UUID insertEffect(UUID chainId, long blockNumber, String blockHash, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO chain_effect
                  (chain_config_id, block_number, block_hash, source_event_key, module_name,
                   effect_type, entity_type, entity_id, category, status)
                VALUES (?, ?, ?, ?, 'test', 'TEST_EFFECT', 'TestEntity', ?, 'RECOMPUTE', 'ACTIVE')
                RETURNING id
                """, UUID.class, chainId, blockNumber, blockHash,
                "test:" + chainId + ":" + blockNumber + ":" + suffix, UUID.randomUUID());
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM chain_effect WHERE id = ?", String.class, id);
    }
}
