package de.makibytes.registerwerk.finality.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface BlockFinalityRepository extends JpaRepository<BlockFinality, UUID> {

    Optional<BlockFinality> findByChainConfigIdAndBlockNumber(UUID chainConfigId, long blockNumber);

    List<BlockFinality> findByChainConfigIdAndBlockNumberGreaterThanEqual(UUID chainConfigId, long blockNumber);

    /** Bulk-marks every row at or after the fork block ORPHANED — never deletes, mirroring
     *  {@code TokenTransferRepository#markOrphanedFromBlock}'s "audit trail, not erasure"
     *  convention. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE BlockFinality b SET b.level = 'ORPHANED' "
            + "WHERE b.chainConfigId = :chainConfigId AND b.blockNumber >= :forkBlockNumber "
            + "AND b.level <> 'ORPHANED'")
    int markOrphanedFromBlock(@Param("chainConfigId") UUID chainConfigId, @Param("forkBlockNumber") long forkBlockNumber);
}
