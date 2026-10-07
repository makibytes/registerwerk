package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves, against a real Postgres 18.6 container, the two properties {@link NonceCoordinator}
 * exists for: (1) two callers racing on the same {@code (chainId, senderAddress)} — from
 * separate JDBC connections, standing in for separate backend replicas — never receive the same
 * nonce, and (2) a failed submission does not advance the durable lease, so the same nonce is
 * correctly retried.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("NonceCoordinator — cross-instance nonce safety")
class NonceCoordinatorIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @Autowired
    private NonceCoordinator coordinator;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final long CHAIN_ID = 11155111L; // Sepolia

    @Test
    @DisplayName("a lease behind the chain-reported nonce adopts the chain's higher value; "
            + "once ahead, the lease itself is trusted over a stale chain read")
    void selfHealsToChainThenTrustsLeaseOnceAhead() throws Exception {
        String address = "0x" + "aa".repeat(20);

        // First-ever use: no lease row yet, so the chain's reported nonce (5) is used verbatim.
        BigInteger first = coordinator.withNonce(CHAIN_ID, address, () -> BigInteger.valueOf(5),
                nonce -> nonce);
        assertThat(first).isEqualTo(BigInteger.valueOf(5));

        // Second call: the lease is now 6 (5+1), but the supplied "chain" nonce is a stale 5
        // (simulating an RPC node that hasn't yet seen the first tx propagate). The lease must
        // win, since it reflects the up-to-date fleet-wide state.
        BigInteger second = coordinator.withNonce(CHAIN_ID, address, () -> BigInteger.valueOf(5),
                nonce -> nonce);
        assertThat(second).isEqualTo(BigInteger.valueOf(6));
    }

    @Test
    @DisplayName("H10: a direct send's nonce is not handed out again once the lease has aged and the chain read lags")
    void directSendNonceIsNotReusedAfterLeaseAgesWhileTheReadLags() throws Exception {
        String address = "0x" + "a1".repeat(20);

        // a direct (non-outbox) send takes nonce 5; the lease moves to 6
        BigInteger sent = coordinator.withNonce(CHAIN_ID, address, () -> BigInteger.valueOf(5), nonce -> nonce);
        assertThat(sent).isEqualTo(BigInteger.valueOf(5));

        // more than the repair grace passes without another send, and the node the failover read picked
        // is lagging: it has not seen transaction 5 yet, so it still reports 5
        jdbc.update("UPDATE wallet_nonce_lease SET updated_at = ? WHERE chain_id = ? AND sender_address = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3_600)), CHAIN_ID, address);

        BigInteger next = coordinator.withNonce(CHAIN_ID, address, () -> BigInteger.valueOf(5), nonce -> nonce);

        // before the fix the lease was "repaired" back to 5 - the pending transaction would be replaced
        assertThat(next).as("nonce 5 already belongs to the direct send").isEqualTo(BigInteger.valueOf(6));
    }

    // ── H10: lease repair needs every node's reading and never reuses a nonce with a known transaction ──

    private static final String TX_5 = "0x" + "5".repeat(64);
    private static final String TX_3 = "0x" + "3".repeat(64);

    private void ageLease(long chainId, String address) {
        jdbc.update("UPDATE wallet_nonce_lease SET updated_at = ? WHERE chain_id = ? AND sender_address = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3_600)), chainId, address);
    }

    /** A direct send exactly as EvmContractService does it: register inside the lock, then "broadcast". */
    private BigInteger directSend(long chainId, String address, long laggingRead, String txHash) throws Exception {
        return coordinator.withNonce(chainId, address, () -> BigInteger.valueOf(laggingRead), nonce -> {
            coordinator.registerDirectSubmission(chainId, address, nonce, txHash, "SEND");
            return nonce;
        });
    }

    private static NonceCoordinator.ChainNonceSource nodes(long laggingRead, java.util.Optional<BigInteger> everyNode,
            java.util.function.Predicate<String> knows) {
        return NonceCoordinator.ChainNonceSource.of(() -> BigInteger.valueOf(laggingRead), () -> everyNode, knows);
    }

    @Test
    @DisplayName("H10: a registered direct send blocks the repair while any node still knows its transaction")
    void liveDirectSendBlocksLeaseRepair() throws Exception {
        String address = "0x" + "a2".repeat(20);
        long chain = CHAIN_ID + 2;
        assertThat(directSend(chain, address, 5, TX_5)).isEqualTo(BigInteger.valueOf(5));
        ageLease(chain, address);

        // the failover read lags (5); every node reports pending 5 as well (the tx sits in a mempool that
        // does not count it yet) but one node KNOWS the transaction
        BigInteger next = coordinator.withNonce(chain, address,
                nodes(5, java.util.Optional.of(BigInteger.valueOf(5)), TX_5::equals), nonce -> nonce);

        assertThat(next).as("nonce 5 belongs to a live direct send").isEqualTo(BigInteger.valueOf(6));
    }

    @Test
    @DisplayName("H10: a direct send whose transaction no node knows any more is a dropped one - the lease is repaired")
    void droppedDirectSendDoesNotBlockLeaseRepair() throws Exception {
        String address = "0x" + "a3".repeat(20);
        long chain = CHAIN_ID + 3;
        assertThat(directSend(chain, address, 5, TX_5)).isEqualTo(BigInteger.valueOf(5));
        ageLease(chain, address);

        BigInteger next = coordinator.withNonce(chain, address,
                nodes(5, java.util.Optional.of(BigInteger.valueOf(5)), hash -> false), nonce -> nonce);

        assertThat(next).as("nobody holds nonce 5 any more").isEqualTo(BigInteger.valueOf(5));
    }

    @Test
    @DisplayName("H10: a ledger entry below the nodes' pending count is consumed and never blocks")
    void consumedDirectSendDoesNotBlockRepair() throws Exception {
        String address = "0x" + "a4".repeat(20);
        long chain = CHAIN_ID + 4;
        // direct send at nonce 3 (mined long ago: the nodes' pending count is 5), then two sends that were dropped
        assertThat(directSend(chain, address, 3, TX_3)).isEqualTo(BigInteger.valueOf(3));
        jdbc.update("UPDATE wallet_nonce_lease SET next_nonce = 7 WHERE chain_id = ? AND sender_address = ?", chain, address);
        ageLease(chain, address);

        BigInteger next = coordinator.withNonce(chain, address,
                nodes(5, java.util.Optional.of(BigInteger.valueOf(5)), TX_3::equals), nonce -> nonce);

        assertThat(next).isEqualTo(BigInteger.valueOf(5));
    }

    @Test
    @DisplayName("H10: when any node cannot answer, the lease is not repaired (the missing node may hold the tx)")
    void unreadableNodeBlocksLeaseRepair() throws Exception {
        String address = "0x" + "a5".repeat(20);
        long chain = CHAIN_ID + 5;
        jdbc.update("INSERT INTO wallet_nonce_lease (chain_id, sender_address, next_nonce, updated_at) VALUES (?, ?, 10, ?)",
                chain, address, java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3_600)));

        BigInteger unknown = coordinator.withNonce(chain, address,
                nodes(5, java.util.Optional.empty(), hash -> false), nonce -> nonce);
        assertThat(unknown).isEqualTo(BigInteger.TEN);

        ageLease(chain, address);
        BigInteger failing = coordinator.withNonce(chain, address,
                NonceCoordinator.ChainNonceSource.of(() -> BigInteger.valueOf(5),
                        () -> { throw new java.io.IOException("node down"); }, hash -> false),
                nonce -> nonce);
        assertThat(failing).isEqualTo(BigInteger.valueOf(11));
    }

    @Test
    @DisplayName("H10: a node that is already at or ahead of the lease wins over the lagging read")
    void aNodeAheadOfTheLeaseWins() throws Exception {
        String address = "0x" + "a6".repeat(20);
        long chain = CHAIN_ID + 6;
        jdbc.update("INSERT INTO wallet_nonce_lease (chain_id, sender_address, next_nonce, updated_at) VALUES (?, ?, 10, ?)",
                chain, address, java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3_600)));

        BigInteger next = coordinator.withNonce(chain, address,
                nodes(5, java.util.Optional.of(BigInteger.valueOf(12)), hash -> false), nonce -> nonce);

        assertThat(next).isEqualTo(BigInteger.valueOf(12));
    }

    @Test
    @DisplayName("H10: registering a direct send is idempotent per hash, works outside a lock, and prunes by retention")
    void directLedgerRegistrationAndRetention() throws Exception {
        String address = "0x" + "a7".repeat(20);
        long chain = CHAIN_ID + 7;
        String old = "0x" + "7".repeat(64);
        jdbc.update("INSERT INTO evm_direct_submission (chain_id, sender_address, nonce, tx_hash, kind, created_at) "
                + "VALUES (?, ?, 1, ?, 'SEND', ?)", chain, address, old,
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(30))));

        coordinator.registerDirectSubmission(chain, address.toUpperCase().replace("0X", "0x"),
                BigInteger.valueOf(2), TX_5, "DEPLOY");
        coordinator.registerDirectSubmission(chain, address, BigInteger.valueOf(2), TX_5, "DEPLOY");   // same hash again

        assertThat(jdbc.queryForList("SELECT tx_hash FROM evm_direct_submission WHERE chain_id = ? AND sender_address = ?",
                String.class, chain, address)).containsExactly(TX_5);
    }

    @Test
    @DisplayName("a failed submission does not advance the lease — the same nonce is retried")
    void failedSubmissionDoesNotAdvanceLease() throws Exception {
        String address = "0x" + "bb".repeat(20);

        BigInteger first = coordinator.withNonce(CHAIN_ID, address, () -> BigInteger.valueOf(10),
                nonce -> nonce);
        assertThat(first).isEqualTo(BigInteger.valueOf(10));

        assertThatThrownBy(() -> coordinator.withNonce(CHAIN_ID, address, () -> BigInteger.valueOf(10),
                nonce -> { throw new RuntimeException("simulated broadcast failure"); }))
                .hasMessageContaining("simulated broadcast failure");

        // Lease is still 11 (10+1) from the successful first call — the failed attempt above
        // must not have advanced it a second time.
        BigInteger third = coordinator.withNonce(CHAIN_ID, address, () -> BigInteger.valueOf(10),
                nonce -> nonce);
        assertThat(third).isEqualTo(BigInteger.valueOf(11));
    }

    @Test
    @DisplayName("concurrent submissions for the same wallet, from separate connections, never "
            + "receive the same nonce")
    void concurrentSubmissions_neverDuplicateNonce() throws Exception {
        String address = "0x" + "cc".repeat(20);
        int concurrency = 20;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        Set<BigInteger> observedNonces = ConcurrentHashMap.newKeySet();
        AtomicInteger broadcastCount = new AtomicInteger();

        try {
            List<Callable<BigInteger>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                tasks.add(() -> coordinator.withNonce(CHAIN_ID, address,
                        // Every racer reports the same stale "chain" nonce (0) — if the lease
                        // were not correctly serialized/advanced, this would make duplicate
                        // nonces trivial to produce.
                        () -> BigInteger.ZERO,
                        nonce -> {
                            broadcastCount.incrementAndGet();
                            return nonce;
                        }));
            }

            List<Future<BigInteger>> futures = pool.invokeAll(tasks);
            for (Future<BigInteger> f : futures) {
                observedNonces.add(f.get());
            }
        } finally {
            pool.shutdown();
        }

        assertThat(broadcastCount.get()).isEqualTo(concurrency);
        assertThat(observedNonces).hasSize(concurrency);
        // Exactly the contiguous range [0, concurrency) — no gaps, no duplicates.
        Set<BigInteger> expected = new java.util.HashSet<>();
        for (int i = 0; i < concurrency; i++) {
            expected.add(BigInteger.valueOf(i));
        }
        assertThat(observedNonces).isEqualTo(expected);
    }

    @Test
    void durableReservationRollsBackWithItsPayloadTransaction() throws Exception {
        String address = "0x" + "dd".repeat(20);

        transactions.executeWithoutResult(status -> {
            try {
                BigInteger reserved = coordinator.withReservedNonce(CHAIN_ID, address,
                        () -> BigInteger.valueOf(40), nonce -> nonce);
                assertThat(reserved).isEqualTo(BigInteger.valueOf(40));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            status.setRollbackOnly();
        });

        BigInteger retry = coordinator.withNonce(CHAIN_ID, address,
                () -> BigInteger.valueOf(40), nonce -> nonce);
        assertThat(retry).isEqualTo(BigInteger.valueOf(40));
    }

    @Test
    void durableReservationAndImmediateBroadcastShareOneFleetLock() throws Exception {
        String address = "0x" + "ee".repeat(20);
        CountDownLatch reservationHeld = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BigInteger> reserved = pool.submit(() -> transactions.<BigInteger>execute(status -> {
                try {
                    return coordinator.withReservedNonce(CHAIN_ID, address, () -> BigInteger.ZERO, nonce -> {
                        reservationHeld.countDown();
                        if (!allowCommit.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test timed out waiting to commit reservation");
                        }
                        return nonce;
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
            assertThat(reservationHeld.await(5, TimeUnit.SECONDS)).isTrue();

            Future<BigInteger> immediate = pool.submit(() -> coordinator.withNonce(
                    CHAIN_ID, address, () -> BigInteger.ZERO, nonce -> nonce));
            Thread.sleep(150);
            assertThat(immediate.isDone()).isFalse();

            allowCommit.countDown();
            assertThat(reserved.get(5, TimeUnit.SECONDS)).isEqualTo(BigInteger.ZERO);
            assertThat(immediate.get(5, TimeUnit.SECONDS)).isEqualTo(BigInteger.ONE);
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
        }
    }
}
