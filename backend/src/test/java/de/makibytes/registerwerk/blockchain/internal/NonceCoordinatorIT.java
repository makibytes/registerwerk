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
