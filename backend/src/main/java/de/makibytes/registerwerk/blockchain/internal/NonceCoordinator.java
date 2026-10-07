package de.makibytes.registerwerk.blockchain.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

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
 * behavior of always re-reading the pending chain nonce fresh on every attempt.
 *
 * <p><strong>Stuck nonces (P4B-4).</strong> A nonce that was broadcast but never mined is handled by
 * {@code OutboxRecoveryService} (re-broadcast, re-price, operator cancel). What this coordinator adds
 * is <em>lease repair</em>: a lease that leads the chain's pending count while no outbox row holds a
 * nonce in the gap (the transaction was dropped) would otherwise stay ahead forever, because the lease
 * only ever grew; see {@code effectiveNonce}.
 *
 * <p><strong>Lease repair never reuses a nonce that has a known transaction (H10).</strong> Repair used to
 * trust the one chain read the caller made - through the failover client, which may have landed on a lagging
 * node - and to look only at outbox rows, so a still-pending transaction sent <em>directly</em> (immediate
 * submit/send/deploy, no outbox row) could have its nonce handed out again after the grace period and be
 * replaced. Now a repair needs a {@link ChainNonceSource} that reads the pending count from <em>every</em>
 * routable node (the highest wins; any node that cannot answer blocks the repair), and is refused while an
 * outbox row ({@code evm_signed_submission}) or a registered direct send ({@code evm_direct_submission},
 * see {@link #registerDirectSubmission}) holds a nonce in the gap and any node still knows that transaction.
 * A plain {@link ChainNonceSupplier} (no node information) never repairs: the lease stays, which is the safe
 * direction - a gap is cleared by the outbox's explicit cancel / re-price, a reused nonce cannot be undone.
 */
@Component
public class NonceCoordinator {

    private static final Logger log = LoggerFactory.getLogger(NonceCoordinator.class);

    @FunctionalInterface
    public interface ChainNonceSupplier {
        BigInteger fetch() throws Exception;
    }

    /**
     * A chain reading that can also answer what lease repair must know before it may reuse a nonce (H10).
     * {@link #fetch()} is the ordinary (failover, possibly lagging) read used for {@code max(lease, chain)}.
     */
    public interface ChainNonceSource extends ChainNonceSupplier {
        /**
         * The highest {@code eth_getTransactionCount(PENDING)} reported by <em>every</em> routable node of the
         * chain, or empty when that is not known: no node pool, or at least one node could not answer (a node
         * that is down may be the one holding the transaction).
         */
        Optional<BigInteger> authoritativePendingNonce() throws Exception;

        /** True when any node knows {@code txHash} (pending or mined). Must answer {@code true} when it cannot tell. */
        boolean knowsTransaction(String txHash);

        static ChainNonceSource of(ChainNonceSupplier lagging, Callable<Optional<BigInteger>> authoritative,
                Predicate<String> knows) {
            return new ChainNonceSource() {
                @Override public BigInteger fetch() throws Exception { return lagging.fetch(); }
                @Override public Optional<BigInteger> authoritativePendingNonce() throws Exception {
                    return authoritative.call();
                }
                @Override public boolean knowsTransaction(String txHash) { return knows.test(txHash); }
            };
        }
    }

    @FunctionalInterface
    public interface NonceCallback<T> {
        T withNonce(BigInteger nonce) throws Exception;
    }

    /** The lock-holding connection while a {@link #withNonce} callback runs (direct sends register on it). */
    private static final ThreadLocal<Connection> LOCK_CONNECTION = new ThreadLocal<>();

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meters;
    private final java.time.Duration leaseRepairGrace;
    private final java.time.Duration directLedgerRetention;

    @Autowired
    public NonceCoordinator(DataSource dataSource, MeterRegistry meters, OutboxProperties properties) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.meters = meters;
        this.leaseRepairGrace = properties.getLeaseRepairGrace();
        this.directLedgerRetention = properties.getDirectLedgerRetention();
    }

    /** Without metrics wiring and with default lease-repair settings (unit tests, tooling). */
    public NonceCoordinator(DataSource dataSource) {
        this(dataSource, null, new OutboxProperties());
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
        BigInteger nonce = jdbcTemplate.execute((ConnectionCallback<BigInteger>) connection ->
                effectiveNonce(connection, chainId, normalizedAddress, leased, chainNonce, chainNonceSupplier));

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
                BigInteger nonce = effectiveNonce(conn, chainId, normalizedAddress, leased, chainNonce,
                        chainNonceSupplier);

                if (leased != null && chainNonce.compareTo(leased) > 0) {
                    log.info("NonceCoordinator: chain-reported PENDING nonce {} for {}/{} is ahead of the "
                                    + "leased value {} — adopting the chain's higher count.",
                            chainNonce, chainId, normalizedAddress, leased);
                }

                LOCK_CONNECTION.set(conn);
                T result;
                try {
                    result = callback.withNonce(nonce);
                } finally {
                    LOCK_CONNECTION.remove();
                }

                // Only reached if the callback (sign + broadcast) succeeded — a failed attempt
                // must not advance the lease, so the same nonce is correctly retried next time.
                upsertLease(conn, chainId, normalizedAddress, nonce.add(BigInteger.ONE));
                return result;
            } finally {
                releaseAdvisoryLock(conn, lockKey);
            }
        }
    }

    /**
     * Registers a direct (non-outbox) send in the {@code evm_direct_submission} ledger so lease repair can never
     * hand out its nonce again while the transaction is alive (H10). Must be called from inside the
     * {@link NonceCallback} of {@link #withNonce}, <strong>after signing and before broadcasting</strong>: the
     * row then exists even when the broadcast fails ambiguously (the node accepted it but the answer was lost).
     * It reuses the connection that holds the nonce lock, so it costs no extra pooled connection, and it throws
     * on failure - an unregistered direct send must not be broadcast.
     */
    public void registerDirectSubmission(long chainId, String senderAddress, BigInteger nonce, String txHash,
            String kind) {
        String address = senderAddress.toLowerCase(Locale.ROOT);
        Connection held = LOCK_CONNECTION.get();
        if (held != null) {
            try {
                insertDirectSubmission(held, chainId, address, nonce, txHash, kind);
            } catch (SQLException e) {
                throw new IllegalStateException("Could not register direct send " + txHash + ": " + e.getMessage(), e);
            }
            return;
        }
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            insertDirectSubmission(connection, chainId, address, nonce, txHash, kind);
            return null;
        });
    }

    private void insertDirectSubmission(Connection conn, long chainId, String address, BigInteger nonce,
            String txHash, String kind) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO evm_direct_submission (chain_id, sender_address, nonce, tx_hash, kind, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (chain_id, sender_address, tx_hash) DO NOTHING
                """)) {
            ps.setLong(1, chainId);
            ps.setString(2, address);
            ps.setBigDecimal(3, new BigDecimal(nonce));
            ps.setString(4, txHash.toLowerCase(Locale.ROOT));
            ps.setString(5, kind);
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM evm_direct_submission WHERE created_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(directLedgerRetention)));
            ps.executeUpdate();
        }
    }

    /**
     * The nonce to use: normally {@code max(lease, chainPending)}. A lease that leads the chain
     * (P4B-4, scenario "dropped tx after TIMEOUT") would otherwise stay ahead forever - every later
     * transaction sits behind a nonce gap no transaction will ever fill. It is capped back only when
     * <ol>
     *   <li>the lease has not moved for {@code leaseRepairGrace};</li>
     *   <li>the caller's reading is a {@link ChainNonceSource} and <em>every</em> routable node answered with a
     *       pending count below the lease (the highest one is the floor - a single, possibly lagging, node
     *       proves nothing);</li>
     *   <li>no local outbox row still holds a nonce in {@code [floor, lease)}; anything else in that range is
     *       either alive or a stuck row the outbox recovery handles explicitly; and</li>
     *   <li>no registered direct send holds a nonce in that range whose transaction any node still knows (H10).</li>
     * </ol>
     * The caller's upsert then writes {@code nonce + 1}, which is the repair. If any condition fails the lease is
     * kept (the safe direction: a gap is cleared by cancelling, a reused nonce replaces a live transaction).
     */
    private BigInteger effectiveNonce(Connection conn, long chainId, String address,
            BigInteger leased, BigInteger chainNonce, ChainNonceSupplier reading) throws SQLException {
        if (leased == null) {
            return chainNonce;
        }
        if (leased.compareTo(chainNonce) <= 0) {
            return chainNonce;
        }
        Instant updatedAt = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT updated_at FROM wallet_nonce_lease WHERE chain_id = ? AND sender_address = ?")) {
            ps.setLong(1, chainId);
            ps.setString(2, address);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getTimestamp(1) != null) updatedAt = rs.getTimestamp(1).toInstant();
            }
        }
        if (updatedAt == null || updatedAt.plus(leaseRepairGrace).isAfter(Instant.now())) {
            return leased;
        }

        // The lease is a repair candidate. The caller's own read went through the failover client and may have
        // landed on a lagging node, so it is not evidence: ask every node.
        if (!(reading instanceof ChainNonceSource source)) {
            return repairBlocked(chainId, address, leased, chainNonce, "NO_NODE_VIEW");
        }
        BigInteger floor;
        try {
            Optional<BigInteger> authoritative = source.authoritativePendingNonce();
            if (authoritative.isEmpty()) {
                return repairBlocked(chainId, address, leased, chainNonce, "NODE_UNAVAILABLE");
            }
            floor = authoritative.get().max(chainNonce);
        } catch (Exception e) {
            log.warn("NonceCoordinator: could not read the pending nonce from every node for {}/{}: {}",
                    chainId, address, e.getMessage());
            return repairBlocked(chainId, address, leased, chainNonce, "NODE_UNAVAILABLE");
        }
        if (floor.compareTo(leased) >= 0) {
            // At least one node already counts the lease's range as sent: not stale at all.
            return floor;
        }

        long occupying;
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT count(*) FROM evm_signed_submission
                WHERE chain_id = ? AND lower(sender_address) = ? AND status IN ('PREPARED', 'BROADCAST')
                  AND nonce >= ? AND nonce < ?
                """)) {
            ps.setBigDecimal(1, new BigDecimal(BigInteger.valueOf(chainId)));
            ps.setString(2, address);
            ps.setBigDecimal(3, new BigDecimal(floor));
            ps.setBigDecimal(4, new BigDecimal(leased));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                occupying = rs.getLong(1);
            }
        }
        if (occupying > 0) {
            return repairBlocked(chainId, address, leased, floor, "OUTBOX_ROW");
        }

        List<String> directHashes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT tx_hash FROM evm_direct_submission
                WHERE chain_id = ? AND sender_address = ? AND nonce >= ? AND nonce < ?
                """)) {
            ps.setLong(1, chainId);
            ps.setString(2, address);
            ps.setBigDecimal(3, new BigDecimal(floor));
            ps.setBigDecimal(4, new BigDecimal(leased));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) directHashes.add(rs.getString(1));
            }
        }
        for (String hash : directHashes) {
            if (source.knowsTransaction(hash)) {
                return repairBlocked(chainId, address, leased, floor, "DIRECT_SEND");
            }
        }

        log.error("NonceCoordinator: nonce lease {} for {}/{} leads the pending count {} of every node but no outbox "
                        + "row or live direct send holds a nonce in [{}, {}) - the lease is stale (dropped transaction); "
                        + "capping it back to the chain value.", leased, chainId, address, floor, floor, leased);
        if (meters != null) {
            meters.counter("registerwerk.outbox.lease_repaired", "chain", String.valueOf(chainId)).increment();
        }
        return floor;
    }

    private BigInteger repairBlocked(long chainId, String address, BigInteger leased, BigInteger chainNonce,
            String reason) {
        log.warn("NonceCoordinator: nonce lease {} for {}/{} leads the chain reading {} but is NOT repaired ({}): "
                + "a nonce that may belong to a live transaction is never reused.", leased, chainId, address,
                chainNonce, reason);
        if (meters != null) {
            meters.counter("registerwerk.outbox.lease_repair_blocked", "chain", String.valueOf(chainId),
                    "reason", reason).increment();
        }
        return leased;
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
