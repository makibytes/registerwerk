package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Chain quarantine persistence and asset-scope lookup")
class ChainQuarantineStoreIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReorgEpisodeStore episodeStore;
    @Autowired private ChainQuarantineStore quarantineStore;
    @Autowired private TransactionTemplate transactions;

    @Test
    @Transactional
    void activeIncidentIsQueryableAndFreezesAssetsWithEffectProvenance() {
        UUID chainConfigId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config
                  (id, identifier, display_name, chain_type, network_type, rpc_url)
                VALUES (?, ?, 'Quarantine test', 'EVM', 'TESTNET', 'http://localhost:8545')
                """, chainConfigId, "quarantine-" + chainConfigId);

        ReorgObservation observation = new ReorgObservation(
                "1", "incident-1", ReorgObservation.ReorgSeverity.FINALITY_VIOLATION,
                new ReorgObservation.BlockReference(99, "0x99", "0x98", FinalityLevel.SAFE),
                List.of(new ReorgObservation.BlockReference(
                        100, "0xaaa", "0x99", FinalityLevel.FINALIZED)),
                List.of(new ReorgObservation.BlockReference(
                        100, "0xbbb", "0x99", FinalityLevel.PROVISIONAL)),
                Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(episodeStore.claim(chainConfigId, observation)).isTrue();

        quarantineStore.activate(chainConfigId, observation);

        assertThat(quarantineStore.findActive(chainConfigId))
                .get()
                .extracting(active -> active.reorgId(), active -> active.severity())
                .containsExactly("incident-1", ReorgObservation.ReorgSeverity.FINALITY_VIOLATION);
        assertThat(quarantineStore.isAssetAffected(assetId)).isFalse();

        jdbc.update("""
                INSERT INTO chain_effect
                  (chain_config_id, block_number, block_hash, source_event_key, module_name,
                   effect_type, entity_type, entity_id, asset_id, category, status)
                VALUES (?, 100, '0xaaa', ?, 'test', 'TEST_EFFECT', 'Asset', ?, ?, 'RECOMPUTE', 'SETTLED')
                """, chainConfigId, "quarantine-source-" + chainConfigId, UUID.randomUUID(), assetId);

        assertThat(quarantineStore.isAssetAffected(assetId)).isTrue();
    }

    @Test
    @Transactional
    void episodeReplayIsSemanticAndRejectsReusedIdWithDifferentLineage() {
        UUID chainConfigId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config
                  (id, identifier, display_name, chain_type, network_type, rpc_url)
                VALUES (?, ?, 'Episode replay test', 'EVM', 'TESTNET', 'http://localhost:8545')
                """, chainConfigId, "episode-" + chainConfigId);
        Instant observedAt = Instant.parse("2026-01-01T00:00:00Z");
        ReorgObservation first = new ReorgObservation(
                "1", "same-id", ReorgObservation.ReorgSeverity.ROUTINE,
                new ReorgObservation.BlockReference(99, "0xAA", "0x98", FinalityLevel.SAFE),
                List.of(new ReorgObservation.BlockReference(100, "0xBB", "0xAA", FinalityLevel.SAFE)),
                List.of(new ReorgObservation.BlockReference(100, "0xCC", "0xAA", FinalityLevel.PROVISIONAL)),
                observedAt);
        ReorgObservation normalizedReplay = new ReorgObservation(
                "1", "same-id", ReorgObservation.ReorgSeverity.ROUTINE,
                new ReorgObservation.BlockReference(99, "0xaa", "0x98", FinalityLevel.SAFE),
                List.of(new ReorgObservation.BlockReference(100, "0xbb", "0xaa", FinalityLevel.SAFE)),
                List.of(new ReorgObservation.BlockReference(100, "0xcc", "0xaa", FinalityLevel.PROVISIONAL)),
                observedAt);
        ReorgObservation collision = new ReorgObservation(
                "1", "same-id", ReorgObservation.ReorgSeverity.ROUTINE,
                new ReorgObservation.BlockReference(99, "0xaa", "0x98", FinalityLevel.SAFE),
                List.of(new ReorgObservation.BlockReference(100, "0xbb", "0xaa", FinalityLevel.SAFE)),
                List.of(new ReorgObservation.BlockReference(100, "0xdd", "0xaa", FinalityLevel.PROVISIONAL)),
                observedAt);

        assertThat(episodeStore.claim(chainConfigId, first)).isTrue();
        assertThat(episodeStore.claim(chainConfigId, normalizedReplay)).isFalse();
        assertThatThrownBy(() -> episodeStore.claim(chainConfigId, collision))
                .isInstanceOf(de.makibytes.registerwerk.finality.api.ReorgEnvelopeConflictException.class);
    }

    @Test
    void submissionGuardAndQuarantineActivationShareTheSameChainRowLock() throws Exception {
        UUID chainConfigId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config
                  (id, identifier, display_name, chain_type, network_type, rpc_url)
                VALUES (?, ?, 'Submission guard test', 'EVM', 'TESTNET', 'http://localhost:8545')
                """, chainConfigId, "guard-" + chainConfigId);
        ReorgObservation observation = new ReorgObservation(
                "1", "submission-race", ReorgObservation.ReorgSeverity.FINALITY_VIOLATION,
                new ReorgObservation.BlockReference(9, "0x09", "0x08", FinalityLevel.SAFE),
                List.of(new ReorgObservation.BlockReference(10, "0x0a", "0x09", FinalityLevel.FINALIZED)),
                List.of(new ReorgObservation.BlockReference(10, "0x0b", "0x09", FinalityLevel.PROVISIONAL)),
                Instant.now());
        transactions.executeWithoutResult(status -> episodeStore.claim(chainConfigId, observation));

        CountDownLatch submissionOwnsLock = new CountDownLatch(1);
        CountDownLatch releaseSubmission = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var submission = pool.submit(() -> transactions.executeWithoutResult(status -> {
                quarantineStore.requireSubmissionAllowed(chainConfigId);
                submissionOwnsLock.countDown();
                try {
                    if (!releaseSubmission.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }));
            assertThat(submissionOwnsLock.await(5, TimeUnit.SECONDS)).isTrue();

            var activation = pool.submit(() -> transactions.executeWithoutResult(status -> {
                quarantineStore.lockChain(chainConfigId);
                quarantineStore.activate(chainConfigId, observation);
            }));
            Thread.sleep(150);
            assertThat(activation.isDone()).isFalse();

            releaseSubmission.countDown();
            submission.get(5, TimeUnit.SECONDS);
            activation.get(5, TimeUnit.SECONDS);
            assertThat(quarantineStore.findActive(chainConfigId)).isPresent();

            assertThatThrownBy(() -> transactions.executeWithoutResult(
                    status -> quarantineStore.requireSubmissionAllowed(chainConfigId)))
                    .isInstanceOf(de.makibytes.registerwerk.finality.api.ChainQuarantinedException.class);
        } finally {
            releaseSubmission.countDown();
            pool.shutdownNow();
        }
    }
}
