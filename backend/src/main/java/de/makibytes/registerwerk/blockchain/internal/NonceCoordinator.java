package de.makibytes.registerwerk.blockchain.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;

/**
 * Serializes EVM transaction submission per {@code (chainId, senderAddress)} across every
 * backend replica, replacing {@code EvmContractService}'s previous per-JVM {@code synchronized}
 * block — which its own Javadoc documented as unsafe once more than one replica submits from the
 * same operator wallet: two instances could read the same pending nonce and one transaction
 * would silently replace the other. The Helm chart ships {@code replicaCount: 3} plus an HPA to
 * 10 against exactly this signing path, so this was a live bug at the shipped configuration.
 *
 * <p><strong>Mechanism.</strong> A Postgres session-scoped advisory lock
 * ({@code pg_advisory_lock}/{@code pg_advisory_unlock}) keyed on a hash of
 * {@code chainId:senderAddress} is held on a dedicated JDBC connection for the entire
 * decide-nonce → sign → broadcast critical section — the caller's {@link NonceCallback} runs
 * <em>while the lock is held</em>, so two replicas racing on the same wallet are strictly
 * serialized rather than merely racing on a shared row. This mirrors the exact critical section
 * {@code EvmContractService} previously protected with a JVM monitor, just now distributed.
 *
 * <p>Keyed on the numeric EIP-155 chain ID (already resolved/cached per {@code Web3j} client by
 * {@code EvmContractService} for signing), not the internal {@code chain_config} row ID — nonces
 * are a property of the (address, chain) pair on the real network, and this lets every existing
 * {@code submit}/{@code send}/{@code deploy} call site keep working unchanged, without threading
 * a new identifier through the ~25 call sites across the codebase.
 *
 * <p><strong>Why a durable lease table, not just the lock.</strong> The lock alone would only
 * serialize; it would not tell the second replica what nonce to use. {@code wallet_nonce_lease}
 * (V5 migration) holds a durable "next nonce" counter, but is deliberately treated as advisory,
 * not authoritative: on every use it is reconciled against a fresh
 * {@code eth_getTransactionCount(address, PENDING)} read from the chain, and the higher of the
 * two wins. This matters because the chain read can lag behind reality (e.g. a load-balanced RPC
 * endpoint that hasn't yet seen another node's just-broadcast transaction propagate through the
 * mempool) — in that case the lease, updated atomically under the same lock by whichever replica
 * broadcast last, is the more current source. Conversely a lease that was never initialized (a
 * wallet's first-ever use) or that fell behind reality (the row was cleared, or the wallet was
 * used out-of-band) self-heals to the chain's own view, since {@code max(lease, chainNonce)} can
 * never be lower than what the chain actually reports.
 *
 * <p><strong>Failure handling.</strong> The lease is advanced only after {@link NonceCallback}
 * returns successfully — if signing or broadcast fails, the row is left untouched so the exact
 * same nonce is correctly retried on the next call, matching the pre-existing single-instance
 * behavior of always re-reading the pending chain nonce fresh on every attempt. This coordinator
 * does not itself detect or repair a nonce that was successfully broadcast but never mined (a
 * stuck transaction) — that is a separate concern (fee-bump resubmit / cancel-by-self-send),
 * deliberately left to a later piece of work; see {@code blockchain_transaction.block_hash} and
 * {@code BlockchainTransactionCompletionWriter} for the reorg/finality half of that story.
 */
@Component
public class NonceCoordinator {

    private static final Logger log = LoggerFactory.getLogger(NonceCoordinator.class);

    @FunctionalInterface
    public interface ChainNonceSupplier {
        BigInteger fetch() throws Exception;
    }

    @FunctionalInterface
    public interface NonceCallback<T> {
        T withNonce(BigInteger nonce) throws Exception;
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public NonceCoordinator(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * Reserves a nonce in the caller's database transaction and returns a value derived from it.
     *
     * <p>This is the prepare half of the durable signed-transaction outbox. The transaction must
     * persist the signed payload before it commits. Both the nonce lease and that payload then
     * become visible atomically; on rollback neither survives and no RPC submission has happened.
     * The transaction-scoped advisory lock conflicts with {@link #withNonce}'s session lock, so
     * durable and immediate submissions from any backend replica share one nonce sequence.
     */
    public <T> T withReservedNonce(long chainId, String senderAddress,
            ChainNonceSupplier chainNonceSupplier, NonceCallback<T> callback) throws Exception {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Durable nonce reservation requires an active transaction");
        }

        String normalizedAddress = senderAddress.toLowerCase(Locale.ROOT);
        String lockKey = chainId + ":" + normalizedAddress;
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, lockKey);
                statement.execute();
            }
            return null;
        });

        BigInteger leased = jdbcTemplate.query("""
                        SELECT next_nonce FROM wallet_nonce_lease
                        WHERE chain_id = ? AND sender_address = ?
                        """,
                rs -> rs.next() ? rs.getBigDecimal(1).toBigInteger() : null,
                chainId, normalizedAddress);
        BigInteger chainNonce = chainNonceSupplier.fetch();
        BigInteger nonce = leased == null ? chainNonce : leased.max(chainNonce);

        T result = callback.withNonce(nonce);
        jdbcTemplate.update("""
                        INSERT INTO wallet_nonce_lease (chain_id, sender_address, next_nonce, updated_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (chain_id, sender_address)
                        DO UPDATE SET next_nonce = EXCLUDED.next_nonce, updated_at = EXCLUDED.updated_at
                        """,
                chainId, normalizedAddress, new BigDecimal(nonce.add(BigInteger.ONE)),
                Timestamp.from(Instant.now()));
        return result;
    }

    /**
     * Runs {@code callback} with the next nonce to use for {@code senderAddress} on
     * {@code chainId}, holding a fleet-wide advisory lock on that pair for the duration.
     * {@code chainNonceSupplier} is only ever invoked once the lock is held, so its RPC call
     * cannot itself race a concurrent submission — it exists purely to reconcile the durable
     * lease against current on-chain reality (see class Javadoc).
     */
    public <T> T withNonce(long chainId, String senderAddress,
            ChainNonceSupplier chainNonceSupplier, NonceCallback<T> callback) throws Exception {
        String normalizedAddress = senderAddress.toLowerCase(Locale.ROOT);
        String lockKey = chainId + ":" + normalizedAddress;

        try (Connection conn = dataSource.getConnection()) {
            acquireAdvisoryLock(conn, lockKey);
            try {
                BigInteger leased = readLease(conn, chainId, normalizedAddress);
                BigInteger chainNonce = chainNonceSupplier.fetch();
                BigInteger nonce = (leased == null) ? chainNonce : leased.max(chainNonce);

                if (leased != null && chainNonce.compareTo(leased) > 0) {
                    log.info("NonceCoordinator: chain-reported PENDING nonce {} for {}/{} is ahead of the "
                                    + "leased value {} — adopting the chain's higher count.",
                            chainNonce, chainId, normalizedAddress, leased);
                }

                T result = callback.withNonce(nonce);

                // Only reached if the callback (sign + broadcast) succeeded — a failed attempt
                // must not advance the lease, so the same nonce is correctly retried next time.
                upsertLease(conn, chainId, normalizedAddress, nonce.add(BigInteger.ONE));
                return result;
            } finally {
                releaseAdvisoryLock(conn, lockKey);
            }
        }
    }

    private void acquireAdvisoryLock(Connection conn, String lockKey) throws SQLException {
        // hashtextextended(text, seed) returns a bigint hash directly — the natural fit for
        // pg_advisory_lock(bigint), unlike hashtext()'s 32-bit int4 result.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
            ps.setString(1, lockKey);
            ps.execute();
        }
    }

    private void releaseAdvisoryLock(Connection conn, String lockKey) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
            ps.setString(1, lockKey);
            ps.execute();
        } catch (Exception e) {
            // The connection is about to be closed/returned to the pool either way; a failed
            // unlock here is logged, not rethrown, so it never masks the real result/exception
            // from the try block above.
            log.warn("NonceCoordinator: failed to release advisory lock for {}: {}", lockKey, e.getMessage());
        }
    }

    private BigInteger readLease(Connection conn, long chainId, String address) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT next_nonce FROM wallet_nonce_lease WHERE chain_id = ? AND sender_address = ?")) {
            ps.setLong(1, chainId);
            ps.setString(2, address);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1).toBigInteger() : null;
            }
        }
    }

    private void upsertLease(Connection conn, long chainId, String address, BigInteger nextNonce)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO wallet_nonce_lease (chain_id, sender_address, next_nonce, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (chain_id, sender_address)
                DO UPDATE SET next_nonce = EXCLUDED.next_nonce, updated_at = EXCLUDED.updated_at
                """)) {
            ps.setLong(1, chainId);
            ps.setString(2, address);
            ps.setBigDecimal(3, new BigDecimal(nextNonce));
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }
}
