package de.makibytes.registerwerk.blockchain.internal.tx;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvmSignedSubmissionRepository extends JpaRepository<EvmSignedSubmission, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from EvmSignedSubmission s where s.id = :id")
    Optional<EvmSignedSubmission> findByIdForUpdate(@Param("id") UUID id);

    Optional<EvmSignedSubmission> findByTxHash(String txHash);

    List<EvmSignedSubmission> findTop100ByStatusOrderByCreatedAtAsc(EvmSignedSubmission.Status status);
}
