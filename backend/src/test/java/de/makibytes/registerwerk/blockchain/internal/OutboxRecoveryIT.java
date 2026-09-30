package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransaction;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionRepository;
import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmissionRepository;
import de.makibytes.registerwerk.blockchain.api.EvmFinalityResolver;
import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P4B-4 / P4B-5 against a real PostgreSQL: the fair dispatch page (poisoned rows do not starve other
 * signers), nonce-lease repair, the V22 active-nonce uniqueness, and reconciliation of a transaction
 * that was marked TIMEOUT and then mined.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Durable outbox recovery - fairness, lease repair, late-mined TIMEOUT")
class OutboxRecoveryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @MockitoBean BlockchainClientRegistry clientRegistry;
    @MockitoBean EvmFinalityResolver finalityResolver;

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired DurableEvmSubmissionService submissions;
    @Autowired EvmSignedSubmissionRepository outbox;
    @Autowired NonceCoordinator coordinator;
    @Autowired BlockchainTransactionService txService;
    @Autowired BlockchainTransactionRepository txRepository;

    private UUID chain() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, enabled)
                VALUES (?, ?, 'Outbox IT Chain', 'EVM', 'TESTNET', 'http://localhost:8545', true)
                """, id, "outbox-it-" + id);
        return id;
    }

    private static String hex(int seed) {
        return "0x" + String.format("%064x", BigInteger.valueOf(seed).add(BigInteger.valueOf(System.nanoTime())));
    }

    private static String signer(int n) {
        return "0x" + String.format("%040x", n);
    }

    private UUID row(UUID chainConfigId, String sender, long chainId, long nonce, String status, Instant createdAt,
            Instant nextAttemptAt, int attempts) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evm_signed_submission (id, chain_config_id, chain_id, sender_address, nonce, tx_hash,
                    signed_payload, status, chain_name, network, contract_address, method_name, attempt_count,
                    created_at, next_attempt_at, broadcast_at)
                VALUES (?, ?, ?, ?, ?, ?, '0x010203', ?, 'ETHEREUM', 'TESTNET', ?, 'registerIdentity', ?, ?, ?, ?)
                """, id, chainConfigId, chainId, sender, nonce, hex((int) nonce), status, signer(999), attempts,
                Timestamp.from(createdAt), nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt),
                "BROADCAST".equals(status) ? Timestamp.from(createdAt) : null);
        return id;
    }

    @Test
    @DisplayName("150 poisoned rows of one signer do not starve a newer valid row of another signer")
    void poisonedPayloadsDoNotStarveOtherSigners() {
        UUID cfg = chain();
        String poisoned = signer(0xA0);
        String healthy = signer(0xB0);
        Instant old = Instant.now().minusSeconds(86_400);
        IntStream.range(0, 150).forEach(n ->
                row(cfg, poisoned, 8_001L, n, "PREPARED", old.plusSeconds(n), null, 30));
        UUID valid = row(cfg, healthy, 8_001L, 0, "PREPARED", Instant.now(), null, 0);

        // The behaviour before P4B-4: a global oldest-100 page never reaches the newer valid row.
        assertThat(outbox.findTop100ByStatusOrderByCreatedAtAsc(
                de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmission.Status.PREPARED))
                .extracting(r -> r.getId()).doesNotContain(valid);

        var page = submissions.dispatchCandidates();

        assertThat(page).extracting(DurableEvmSubmissionService.Candidate::id).contains(valid);
        long fromPoisoned = page.stream().filter(c -> c.signer().endsWith(poisoned)).count();
        assertThat(fromPoisoned).as("per-signer cap keeps the poisoned signer from filling the page")
                .isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("rows in back-off are skipped, later eligible nonces of the same signer are still dispatched")
    void backedOffRowsAreSkippedNotWaitedFor() {
        UUID cfg = chain();
        String s = signer(0xC0);
        UUID head = row(cfg, s, 8_002L, 0, "PREPARED", Instant.now().minusSeconds(600),
                Instant.now().plusSeconds(300), 5);
        UUID next = row(cfg, s, 8_002L, 1, "PREPARED", Instant.now().minusSeconds(500), null, 0);

        var ids = submissions.dispatchCandidates().stream().map(DurableEvmSubmissionService.Candidate::id).toList();

        assertThat(ids).contains(next).doesNotContain(head);
    }

    @Test
    @DisplayName("a stale lease ahead of the chain with no outbox row in the gap is capped back to the chain value")
    void staleLeaseIsRepaired() throws Exception {
        String address = signer(0xD0);
        long chainId = 8_003L;
        // lease says next nonce is 10; the chain (all nodes) says pending is 5; nothing local holds 5..9
        jdbc.update("INSERT INTO wallet_nonce_lease (chain_id, sender_address, next_nonce, updated_at) VALUES (?, ?, 10, ?)",
                chainId, address, Timestamp.from(Instant.now().minusSeconds(3_600)));

        BigInteger used = transactions.execute(status -> {
            try {
                return coordinator.withReservedNonce(chainId, address, () -> BigInteger.valueOf(5), n -> n);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(used).isEqualTo(BigInteger.valueOf(5));
        assertThat(jdbc.queryForObject("SELECT next_nonce FROM wallet_nonce_lease WHERE chain_id = ? AND sender_address = ?",
                BigInteger.class, chainId, address)).isEqualTo(BigInteger.valueOf(6));
    }

    @Test
    @DisplayName("a lease ahead of the chain is kept while an outbox row still holds a nonce in the gap")
    void leaseIsKeptWhileAnOutboxRowOccupiesTheGap() throws Exception {
        UUID cfg = chain();
        String address = signer(0xD1);
        long chainId = 8_004L;
        jdbc.update("INSERT INTO wallet_nonce_lease (chain_id, sender_address, next_nonce, updated_at) VALUES (?, ?, 10, ?)",
                chainId, address, Timestamp.from(Instant.now().minusSeconds(3_600)));
        row(cfg, address, chainId, 7, "BROADCAST", Instant.now().minusSeconds(3_000), null, 1);

        BigInteger used = transactions.execute(status -> {
            try {
                return coordinator.withReservedNonce(chainId, address, () -> BigInteger.valueOf(5), n -> n);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(used).isEqualTo(BigInteger.TEN);
    }

    @Test
    @DisplayName("a freshly advanced lease is trusted over a lagging chain read (grace period)")
    void freshLeaseIsNotCapped() throws Exception {
        String address = signer(0xD2);
        long chainId = 8_005L;
        jdbc.update("INSERT INTO wallet_nonce_lease (chain_id, sender_address, next_nonce, updated_at) VALUES (?, ?, 10, now())",
                chainId, address);

        BigInteger used = transactions.execute(status -> {
            try {
                return coordinator.withReservedNonce(chainId, address, () -> BigInteger.valueOf(5), n -> n);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(used).isEqualTo(BigInteger.TEN);
    }

    @Test
    @DisplayName("V22: only ACTIVE rows are unique per (chain, sender, nonce); a superseded row frees the nonce")
    void supersededRowFreesTheNonce() {
        UUID cfg = chain();
        String s = signer(0xE0);
        UUID original = row(cfg, s, 8_006L, 3, "BROADCAST", Instant.now().minusSeconds(100), null, 1);

        assertThatThrownBy(() -> row(cfg, s, 8_006L, 3, "PREPARED", Instant.now(), null, 0))
                .isInstanceOf(DuplicateKeyException.class);

        jdbc.update("UPDATE evm_signed_submission SET status = 'SUPERSEDED' WHERE id = ?", original);
        UUID replacement = row(cfg, s, 8_006L, 3, "PREPARED", Instant.now(), null, 0);
        assertThat(replacement).isNotNull();
    }

    @Test
    @DisplayName("a TIMEOUT tx that is mined later is reconciled to SUCCESS; until then it is not a confirmed failure")
    void lateMinedTimeoutIsReconciled() throws Exception {
        UUID cfg = chain();
        String hash = hex(7);
        BlockchainTransaction tx = new BlockchainTransaction();
        tx.setTxHash(hash);
        tx.setStatus(BlockchainTransaction.Status.TIMEOUT);
        tx.setChain("ETHEREUM");
        tx.setNetwork("TESTNET");
        tx.setMethodName("registerIdentity");
        tx.setChainConfigId(cfg);
        tx.setCompletedAt(Instant.now().minusSeconds(600));
        tx.setErrorMessage("Transaction not mined within 900s");
        txRepository.save(tx);

        // The scheduled poller is ShedLock-guarded (lockAtLeastFor); call the unproxied bean directly.
        BlockchainTransactionService poller = AopTestUtils.getUltimateTargetObject(txService);
        Web3j web3j = mock(Web3j.class, RETURNS_DEEP_STUBS);
        when(clientRegistry.getEvmClientByIdentifier("ETHEREUM_TESTNET")).thenReturn(web3j);
        when(finalityResolver.levelOf(any(String.class), any(Web3j.class), anyLong())).thenReturn(FinalityLevel.FINALIZED);
        when(web3j.ethGetTransactionReceipt(hash).send().getTransactionReceipt()).thenReturn(Optional.empty());

        poller.pollTimedOutTransactions();
        assertThat(txRepository.findByTxHash(hash).orElseThrow().getStatus())
                .isEqualTo(BlockchainTransaction.Status.TIMEOUT);
        assertThat(txService.isConfirmedFailure(hash)).isFalse();
        assertThat(txService.isAwaitingChain(hash)).isTrue();

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setBlockNumber("0x64");
        receipt.setBlockHash(hex(100));
        receipt.setStatus("0x1");
        receipt.setGasUsed("0x5208");
        when(web3j.ethGetTransactionReceipt(hash).send().getTransactionReceipt()).thenReturn(Optional.of(receipt));

        // The unproxied target has no @Transactional; the audit recorder is an async AFTER_COMMIT listener,
        // so run the poll inside a transaction and wait for the listener.
        transactions.executeWithoutResult(status -> poller.pollTimedOutTransactions());
        for (int i = 0; i < 100 && jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE event_type = 'BLOCKCHAIN_TX_LATE_MINED_SUCCESS'", Long.class) == 0; i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        BlockchainTransaction reconciled = txRepository.findByTxHash(hash).orElseThrow();
        assertThat(reconciled.getStatus()).isEqualTo(BlockchainTransaction.Status.SUCCESS);
        assertThat(reconciled.getLateMinedAt()).isNotNull();
        assertThat(reconciled.getBlockNumber()).isEqualTo(100L);
        assertThat(txService.isConfirmedSuccess(hash)).isTrue();
        // AuditEventRecorder is an @Async AFTER_COMMIT listener: wait for it instead of reading immediately.
        long deadline = System.currentTimeMillis() + 10_000;
        Long audited = 0L;
        while (System.currentTimeMillis() < deadline) {
            audited = jdbc.queryForObject(
                    "SELECT count(*) FROM audit_event WHERE event_type = 'BLOCKCHAIN_TX_LATE_MINED_SUCCESS'", Long.class);
            if (audited != null && audited >= 1L) break;
            Thread.sleep(100);
        }
        assertThat(audited).isGreaterThanOrEqualTo(1L);
    }
}
