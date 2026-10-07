package de.makibytes.registerwerk.erc3643.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface HolderBlockFreezeRepository extends JpaRepository<HolderBlockFreeze, UUID> {

    Optional<HolderBlockFreeze> findByHolderBlockIdAndDeploymentIdAndWalletAddress(
            UUID holderBlockId, UUID deploymentId, String walletAddress);

    List<HolderBlockFreeze> findByHolderBlockId(UUID holderBlockId);

    List<HolderBlockFreeze> findByTxId(UUID txId);

    List<HolderBlockFreeze> findByStatusIn(Collection<HolderBlockFreeze.Status> statuses);

    long countByStatus(HolderBlockFreeze.Status status);

    /** Serialises the transaction-status listener, the sweep and the reconcile job on one row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM HolderBlockFreeze f WHERE f.id = :id")
    Optional<HolderBlockFreeze> findByIdForUpdate(@Param("id") UUID id);
}
