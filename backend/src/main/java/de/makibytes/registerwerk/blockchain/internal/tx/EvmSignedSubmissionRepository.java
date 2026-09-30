package de.makibytes.registerwerk.blockchain.internal.tx;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvmSignedSubmissionRepository extends JpaRepository<EvmSignedSubmission, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from EvmSignedSubmission s where s.id = :id")
    Optional<EvmSignedSubmission> findByIdForUpdate(@Param("id") UUID id);

    Optional<EvmSignedSubmission> findByTxHash(String txHash);

    /** P4B-7: the submission an earlier attempt of the same HTTP request (same Idempotency-Key) already made. */
    Optional<EvmSignedSubmission> findByIdempotencyKey(String idempotencyKey);

    /** P4C-5: has this operator key ever signed a chain transaction (wallet-delete guard)? */
    boolean existsBySenderAddressIgnoreCase(String senderAddress);

    List<EvmSignedSubmission> findTop100ByStatusOrderByCreatedAtAsc(EvmSignedSubmission.Status status);

    /** Every row (any status) that ever used this nonce for this signer: the original, re-prices, cancels. */
    List<EvmSignedSubmission> findByChainIdAndSenderAddressIgnoreCaseAndNonceOrderByCreatedAtAsc(
            BigInteger chainId, String senderAddress, BigInteger nonce);

    List<EvmSignedSubmission> findTop200ByStatusAndBroadcastAtBeforeOrderByBroadcastAtAsc(
            EvmSignedSubmission.Status status, Instant cutoff);

    /** A dispatch candidate together with the signer it belongs to (head-of-line bookkeeping). */
    interface DispatchCandidate {
        UUID getId();
        BigDecimal getChainId();
        String getSenderAddress();
    }

    /**
     * Fair dispatch page (P4B-4). Rows in back-off are not eligible (skipped, not waited for). Per
     * signer the lowest eligible nonces are taken (a later nonce cannot mine before an earlier one), at
     * most {@code perSigner} of them, and the page is interleaved round-robin across signers (row rank
     * first). One signer's poisoned rows can therefore never fill the page and starve another signer
     * or chain, however many there are.
     */
    @Query(value = """
            SELECT t.id AS id, t.chain_id AS chainId, t.sender_address AS senderAddress
            FROM (
                SELECT s.id, s.chain_id, s.sender_address, s.nonce, s.created_at,
                       row_number() OVER (PARTITION BY s.chain_id, s.sender_address ORDER BY s.nonce) AS rn
                FROM evm_signed_submission s
                WHERE s.status = 'PREPARED' AND (s.next_attempt_at IS NULL OR s.next_attempt_at <= :now)
            ) t
            WHERE t.rn <= :perSigner
            ORDER BY t.rn, t.created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<DispatchCandidate> findDispatchCandidates(@Param("now") Instant now,
            @Param("perSigner") int perSigner, @Param("limit") int limit);

    interface SignerBacklog {
        BigDecimal getChainId();
        String getSenderAddress();
        long getPreparedCount();
        Instant getOldestCreatedAt();
    }

    @Query(value = """
            SELECT s.chain_id AS chainId, s.sender_address AS senderAddress,
                   count(*) AS preparedCount, min(s.created_at) AS oldestCreatedAt
            FROM evm_signed_submission s
            WHERE s.status = 'PREPARED'
            GROUP BY s.chain_id, s.sender_address
            """, nativeQuery = true)
    List<SignerBacklog> preparedBacklogPerSigner();

    /** Stuck rows of one chain: PREPARED longer than the cutoff, or BROADCAST but still unmined. */
    @Query(value = """
            SELECT s.* FROM evm_signed_submission s
            WHERE s.chain_config_id = :chainConfigId
              AND ((s.status = 'PREPARED' AND s.created_at < :cutoff)
                   OR (s.status = 'BROADCAST' AND EXISTS (
                        SELECT 1 FROM blockchain_transaction b
                        WHERE b.tx_hash = s.tx_hash AND b.status IN ('PENDING', 'TIMEOUT')
                          AND b.created_at < :cutoff)))
            ORDER BY s.sender_address, s.nonce, s.created_at
            """, nativeQuery = true)
    List<EvmSignedSubmission> findStuck(@Param("chainConfigId") UUID chainConfigId, @Param("cutoff") Instant cutoff);

    /** All chains, for the alert sweep. */
    @Query(value = """
            SELECT s.* FROM evm_signed_submission s
            WHERE (s.status = 'PREPARED' AND s.created_at < :cutoff)
               OR (s.status = 'BROADCAST' AND EXISTS (
                        SELECT 1 FROM blockchain_transaction b
                        WHERE b.tx_hash = s.tx_hash AND b.status IN ('PENDING', 'TIMEOUT')
                          AND b.created_at < :cutoff))
            """, nativeQuery = true)
    List<EvmSignedSubmission> findAllStuck(@Param("cutoff") Instant cutoff);
}
