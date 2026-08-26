package de.makibytes.registerwerk.infrastructure;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves, against a real Postgres 18.6 container, the partition and retention behavior.
 * <ol>
 *   <li>{@code token_transfer} is genuinely RANGE-partitioned by {@code occurred_at} after
 *       the baseline schema: thousands of rows spread across several months all
 *       land in the correct monthly partition (none in the DEFAULT partition), and
 *       {@code rw_retire_partitions} can DETACH old partitions — proven here on token_transfer
 *       as the "non-regulated table" the task background calls for, since audit_event is
 *       deliberately never wired to retirement — without losing a single row (everything is
 *       still present, just under the partition's new "_archived_" name).</li>
 *   <li>{@link RetentionSweepJob}, run directly (not waiting for its cron), deletes rows that
 *       are both no-longer-live AND past their configured retention window, while leaving rows
 *       that are still live (unused/unexpired) or still within their window untouched — proven
 *       across every required target (login_attempt, wallet_bind_challenge, onboarding_token,
 *       app_user_action_token, webhook_delivery, event_publication).</li>
 * </ol>
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Partitioning + retention sweep integration test")
class RetentionAndPartitioningIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RetentionSweepJob sweepJob;

    private UUID chainConfigId;

    private void seedChainConfigOnce() {
        if (chainConfigId != null) {
            return;
        }
        chainConfigId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, enabled)
                VALUES (?, 'retention-it-chain', 'Retention IT Chain', 'EVM', 'TESTNET', 'http://localhost:8545', true)
                """, chainConfigId);
    }

    @Test
    @Order(1)
    @DisplayName("token_transfer rows spanning several months land in the correct monthly partition, not DEFAULT")
    void tokenTransferPartitionsCorrectly() {
        seedChainConfigOnce();

        // 3,000 rows spread across today through ~6 months ahead (well within "thousands, not
        // millions"). Forward-looking on purpose: rw_ensure_monthly_partitions (called both by
        // the V3 migration itself and by PartitionMaintenanceJob.onStartup() during this test's
        // context boot) only ever bootstraps the current month and months *ahead* of it, exactly
        // like audit_event_ensure_partitions already does for audit_event — it does not backfill
        // months before whatever data already existed at migration time (this table was empty
        // then, in a fresh test database). That mirrors the real system: new indexer-synced
        // transfers always arrive with occurred_at at-or-near "now", not months in the past.
        jdbc.update("""
                INSERT INTO token_transfer (id, chain_config_id, contract_address, event_type, tx_hash, occurred_at)
                SELECT gen_random_uuid(), ?, '0xRetentionItContract', 'TRANSFER', 'tx-partition-' || gs,
                       now() + ((gs % 180) || ' days')::interval
                FROM generate_series(1, 3000) AS gs
                """, chainConfigId);

        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM token_transfer WHERE tx_hash LIKE 'tx-partition-%'", Integer.class);
        assertThat(total).isEqualTo(3000);

        // Every row's physical partition (tableoid) must match what its occurred_at implies.
        Integer misrouted = jdbc.queryForObject("""
                SELECT count(*) FROM token_transfer t
                WHERE t.tx_hash LIKE 'tx-partition-%'
                  AND t.tableoid::regclass::text <> ('token_transfer_' || to_char(t.occurred_at, 'YYYY_MM'))
                """, Integer.class);
        assertThat(misrouted).isZero();

        Integer distinctPartitions = jdbc.queryForObject("""
                SELECT count(DISTINCT tableoid) FROM token_transfer WHERE tx_hash LIKE 'tx-partition-%'
                """, Integer.class);
        assertThat(distinctPartitions).isGreaterThan(1);

        Integer inDefault = jdbc.queryForObject(
                "SELECT count(*) FROM token_transfer_default WHERE tx_hash LIKE 'tx-partition-%'", Integer.class);
        assertThat(inDefault).isZero();
    }

    @Test
    @Order(2)
    @DisplayName("rw_retire_partitions DETACHes old token_transfer partitions with zero data loss")
    void retirePartitionsPreservesData() {
        seedChainConfigOnce();

        // rw_ensure_monthly_partitions (and therefore this migration's bootstrap) only ever
        // creates the current month and months *ahead* — there is nothing already 4-5 months in
        // the past for a freshly-migrated empty table to retire. Create two such historical
        // partitions directly (simulating a table that already had old data before this
        // migration/test ran) so there is something genuinely eligible for retirement.
        createHistoricalPartition(5);
        createHistoricalPartition(4);
        jdbc.update("""
                INSERT INTO token_transfer (id, chain_config_id, contract_address, event_type, tx_hash, occurred_at)
                SELECT gen_random_uuid(), ?, '0xRetentionItContract', 'TRANSFER', 'tx-old-' || gs,
                       date_trunc('month', now() - ((4 + (gs % 2)) || ' months')::interval) + interval '3 days'
                FROM generate_series(1, 200) AS gs
                """, chainConfigId);

        long before = jdbc.queryForObject(
                "SELECT count(*) FROM token_transfer WHERE tx_hash LIKE 'tx-old-%'", Long.class);
        assertThat(before).isEqualTo(200L);

        List<String> archived = jdbc.queryForList(
                "SELECT * FROM rw_retire_partitions('token_transfer', 'occurred_at', 3, 'DETACH')", String.class);
        assertThat(archived).as("both partitions 4-5 months old should have been detached (keep_months=3)")
                .hasSize(2);
        assertThat(archived).allSatisfy(name -> assertThat(name).contains("_archived_"));

        long afterInParent = jdbc.queryForObject(
                "SELECT count(*) FROM token_transfer WHERE tx_hash LIKE 'tx-old-%'", Long.class);
        assertThat(afterInParent).isZero();

        long inArchivedTables = 0;
        for (String archivedTable : archived) {
            Long rows = jdbc.queryForObject(
                    "SELECT count(*) FROM " + archivedTable + " WHERE tx_hash LIKE 'tx-old-%'", Long.class);
            inArchivedTables += rows;
        }

        // DETACH must not lose a single row: nothing left in the parent, everything accounted
        // for in the archived (renamed, still fully queryable) tables.
        assertThat(inArchivedTables).isEqualTo(before);
    }

    @Test
    @Order(3)
    @DisplayName("rw_retire_partitions refuses any mode other than DETACH")
    void retirePartitionsRejectsUnsupportedMode() {
        assertThatThrownBy(() -> jdbc.queryForList(
                "SELECT * FROM rw_retire_partitions('token_transfer', 'occurred_at', 1, 'DROP')", String.class))
                .hasMessageContaining("not supported");
    }

    private void createHistoricalPartition(int monthsAgo) {
        jdbc.execute(String.format("""
                DO $$
                DECLARE
                    m DATE := date_trunc('month', now() - INTERVAL '%d months')::DATE;
                BEGIN
                    EXECUTE format(
                        'CREATE TABLE IF NOT EXISTS %%I PARTITION OF token_transfer FOR VALUES FROM (%%L) TO (%%L)',
                        'token_transfer_' || to_char(m, 'YYYY_MM'), m, (m + INTERVAL '1 month')::DATE
                    );
                END;
                $$;
                """, monthsAgo));
    }

    @Test
    @Order(4)
    @DisplayName("retention sweep purges only rows that are both no-longer-live and past their window")
    void retentionSweepRespectsLegalHoldWindows() {
        seedLoginAttempts();
        UUID legalEntityId = seedLegalEntity();
        UUID appUserId = seedAppUser();
        seedWalletBindChallenges(legalEntityId);
        seedOnboardingTokens(legalEntityId);
        seedAppUserActionTokens(appUserId);
        UUID subscriptionId = seedWebhookSubscription();
        seedWebhookDeliveries(subscriptionId);
        seedEventPublications();

        sweepJob.sweep();

        // login_attempt: only the stale row is gone; recordSuccess-style deletion already keeps
        // the table failure-only, so age is the only criterion.
        assertThat(existsLoginAttempt("recent@retention.test")).isTrue();
        assertThat(existsLoginAttempt("old@retention.test")).isFalse();

        // wallet_bind_challenge: used-long-ago and expired-long-ago rows are gone; a still-live
        // (unused, unexpired) old row and a fresh row both survive.
        assertThat(walletChallengeCount("used-old")).isZero();
        assertThat(walletChallengeCount("expired-old")).isZero();
        assertThat(walletChallengeCount("live-old")).isEqualTo(1L);
        assertThat(walletChallengeCount("live-fresh")).isEqualTo(1L);

        // onboarding_token: same used/expired-vs-live shape.
        assertThat(onboardingTokenCount("used-old")).isZero();
        assertThat(onboardingTokenCount("live-old")).isEqualTo(1L);

        // app_user_action_token: same shape again.
        assertThat(actionTokenCount("consumed-old")).isZero();
        assertThat(actionTokenCount("live-old")).isEqualTo(1L);

        // webhook_delivery: terminal (SUCCESS/FAILED) old rows are gone; PENDING survives
        // regardless of age — a delivery still in flight must never be swept.
        assertThat(webhookDeliveryCount("success-old")).isZero();
        assertThat(webhookDeliveryCount("failed-old")).isZero();
        assertThat(webhookDeliveryCount("pending-old")).isEqualTo(1L);

        // event_publication: completed-and-old is gone; completed-and-recent and
        // still-outstanding (null completion_date, regardless of age) both survive.
        assertThat(eventPublicationCount("completed-old")).isZero();
        assertThat(eventPublicationCount("completed-recent")).isEqualTo(1L);
        assertThat(eventPublicationCount("outstanding-old")).isEqualTo(1L);
    }

    private void seedLoginAttempts() {
        jdbc.update("INSERT INTO login_attempt (login_key, attempt_count, updated_at) "
                + "VALUES ('old@retention.test', 5, now() - interval '400 days')");
        jdbc.update("INSERT INTO login_attempt (login_key, attempt_count, updated_at) "
                + "VALUES ('recent@retention.test', 3, now())");
    }

    private boolean existsLoginAttempt(String key) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM login_attempt WHERE login_key = ?", Integer.class, key);
        return count != null && count > 0;
    }

    private UUID seedLegalEntity() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO legal_entity (id, entity_number, type, current_name)
                VALUES (?, ?, 'ISSUER', 'Retention IT Test Entity')
                """, id, "RIT-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID seedAppUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, email) VALUES (?, ?)",
                id, "retention-it-" + id + "@test.local");
        return id;
    }

    private void seedWalletBindChallenges(UUID legalEntityId) {
        // used long ago, created long ago -> swept.
        insertWalletChallenge(legalEntityId, "used-old", "now() - interval '350 days'",
                "now() - interval '399 days'", "now() - interval '400 days'");
        // never used, expired long ago, created long ago -> swept.
        insertWalletChallenge(legalEntityId, "expired-old", "NULL",
                "now() - interval '380 days'", "now() - interval '400 days'");
        // never used, NOT expired (still live), created long ago -> must survive: a live nonce
        // must never be swept purely because of its age.
        insertWalletChallenge(legalEntityId, "live-old", "NULL",
                "now() + interval '1 day'", "now() - interval '400 days'");
        // never used, not expired, fresh -> obviously survives.
        insertWalletChallenge(legalEntityId, "live-fresh", "NULL",
                "now() + interval '1 day'", "now()");
    }

    private void insertWalletChallenge(UUID legalEntityId, String tag, String usedAtExpr,
                                        String expiresAtExpr, String createdAtExpr) {
        jdbc.update(String.format("""
                INSERT INTO wallet_bind_challenge
                    (id, legal_entity_id, chain_config_id, wallet_address, nonce, used_at, expires_at, created_at)
                VALUES
                    (gen_random_uuid(), ?, ?, ?, ?, %s, %s, %s)
                """, usedAtExpr, expiresAtExpr, createdAtExpr),
                legalEntityId, chainConfigId, "0xWallet-" + tag, "nonce-" + tag);
    }

    private long walletChallengeCount(String tag) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM wallet_bind_challenge WHERE wallet_address = ?",
                Long.class, "0xWallet-" + tag);
    }

    private void seedOnboardingTokens(UUID legalEntityId) {
        jdbc.update(String.format("""
                INSERT INTO onboarding_token (id, legal_entity_id, token_hash, used_at, expires_at, issued_at)
                VALUES (gen_random_uuid(), ?, 'hash-used-old', %s, %s, %s)
                """, "now() - interval '350 days'", "now() - interval '399 days'", "now() - interval '400 days'"),
                legalEntityId);
        jdbc.update(String.format("""
                INSERT INTO onboarding_token (id, legal_entity_id, token_hash, used_at, expires_at, issued_at)
                VALUES (gen_random_uuid(), ?, 'hash-live-old', NULL, %s, %s)
                """, "now() + interval '1 day'", "now() - interval '400 days'"),
                legalEntityId);
    }

    private long onboardingTokenCount(String tag) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM onboarding_token WHERE token_hash = ?",
                Long.class, "hash-" + tag);
    }

    private void seedAppUserActionTokens(UUID appUserId) {
        jdbc.update(String.format("""
                INSERT INTO app_user_action_token (id, app_user_id, token_hash, token_type, consumed_at, expires_at, created_at)
                VALUES (gen_random_uuid(), ?, 'token-consumed-old', 'PASSWORD_RESET', %s, %s, %s)
                """, "now() - interval '350 days'", "now() - interval '399 days'", "now() - interval '400 days'"),
                appUserId);
        jdbc.update(String.format("""
                INSERT INTO app_user_action_token (id, app_user_id, token_hash, token_type, consumed_at, expires_at, created_at)
                VALUES (gen_random_uuid(), ?, 'token-live-old', 'PASSWORD_RESET', NULL, %s, %s)
                """, "now() + interval '1 day'", "now() - interval '400 days'"),
                appUserId);
    }

    private long actionTokenCount(String tag) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app_user_action_token WHERE token_hash = ?",
                Long.class, "token-" + tag);
    }

    private UUID seedWebhookSubscription() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO webhook_subscription (id, entity_id, url, secret, event_types)
                VALUES (?, gen_random_uuid(), 'https://example.test/hook', 'sekret', '')
                """, id);
        return id;
    }

    private void seedWebhookDeliveries(UUID subscriptionId) {
        jdbc.update("""
                INSERT INTO webhook_delivery (id, subscription_id, event_type, payload, status, created_at)
                VALUES (gen_random_uuid(), ?, 'success-old', '{}', 'SUCCESS', now() - interval '200 days')
                """, subscriptionId);
        jdbc.update("""
                INSERT INTO webhook_delivery (id, subscription_id, event_type, payload, status, created_at)
                VALUES (gen_random_uuid(), ?, 'failed-old', '{}', 'FAILED', now() - interval '200 days')
                """, subscriptionId);
        jdbc.update("""
                INSERT INTO webhook_delivery (id, subscription_id, event_type, payload, status, created_at)
                VALUES (gen_random_uuid(), ?, 'pending-old', '{}', 'PENDING', now() - interval '200 days')
                """, subscriptionId);
    }

    private long webhookDeliveryCount(String eventTypeTag) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM webhook_delivery WHERE event_type = ?", Long.class, eventTypeTag);
    }

    private void seedEventPublications() {
        jdbc.update("""
                INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date)
                VALUES (gen_random_uuid(), 'completed-old', 'completed-old', '{}', now() - interval '90 days', now() - interval '60 days')
                """);
        jdbc.update("""
                INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date)
                VALUES (gen_random_uuid(), 'completed-recent', 'completed-recent', '{}', now() - interval '2 days', now() - interval '1 day')
                """);
        // event_type looks like a fully-qualified class name (rather than a plain tag like the
        // two rows above) because Spring Modulith's event-publication registry tries to resolve
        // outstanding (completion_date IS NULL) rows back to their event class on context
        // shutdown; an unresolvable name only produces a harmless destroy-time warning, but a
        // realistic-looking name avoids even that log noise.
        jdbc.update("""
                INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date)
                VALUES (gen_random_uuid(), 'outstanding-old', 'de.makibytes.registerwerk.infrastructure.OutstandingOldTestEvent', '{}', now() - interval '90 days', NULL)
                """);
    }

    private long eventPublicationCount(String listenerIdTag) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE listener_id = ?", Long.class, listenerIdTag);
    }
}
