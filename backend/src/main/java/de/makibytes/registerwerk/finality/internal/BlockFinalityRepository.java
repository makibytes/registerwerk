package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.FinalityLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface BlockFinalityRepository extends JpaRepository<BlockFinality, UUID> {

    Optional<BlockFinality> findByChainConfigIdAndBlockNumberAndCanonicalTrue(UUID chainConfigId, long blockNumber);

    Optional<BlockFinality> findByChainConfigIdAndBlockNumberAndBlockHash(
            UUID chainConfigId, long blockNumber, String blockHash);

    List<BlockFinality> findByChainConfigIdAndBlockNumberOrderByObservedAtAscIdAsc(
            UUID chainConfigId, long blockNumber);

    boolean existsByChainConfigIdAndBlockNumberGreaterThanEqualAndCanonicalTrueAndLevel(
            UUID chainConfigId, long forkBlockNumber, FinalityLevel level);

    /** Bulk-marks every row at or after the fork block ORPHANED — never deletes, mirroring
     *  {@code TokenTransferRepository#markOrphanedFromBlock}'s "audit trail, not erasure"
     *  convention. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE BlockFinality b SET b.level = 'ORPHANED', b.canonical = false, "
            + "b.orphanedAt = CURRENT_TIMESTAMP, b.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE b.chainConfigId = :chainConfigId AND b.blockNumber >= :forkBlockNumber "
            + "AND b.canonical = true")
    int markOrphanedFromBlock(@Param("chainConfigId") UUID chainConfigId, @Param("forkBlockNumber") long forkBlockNumber);

    /** Marks only the block identities named by a typed reorg episode. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE BlockFinality b SET b.level = 'ORPHANED', b.canonical = false, "
            + "b.orphanedAt = CURRENT_TIMESTAMP, b.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE b.chainConfigId = :chainConfigId AND b.canonical = true "
            + "AND b.blockHash IN :blockHashes")
    int markCanonicalOrphanedByHashes(
            @Param("chainConfigId") UUID chainConfigId,
            @Param("blockHashes") List<String> blockHashes);

    boolean existsByChainConfigIdAndCanonicalTrueAndLevelAndBlockHashIn(
            UUID chainConfigId, FinalityLevel level, List<String> blockHashes);
}
