package de.makibytes.registerwerk.kyc.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HolderBlockRepository extends JpaRepository<HolderBlock, UUID> {

    List<HolderBlock> findByWalletAddressAndStatus(String walletAddress, HolderBlock.Status status);

    List<HolderBlock> findByEntityIdAndStatus(UUID entityId, HolderBlock.Status status);

    List<HolderBlock> findByWalletAddressAndStatusIn(String walletAddress, java.util.Collection<HolderBlock.Status> statuses);

    List<HolderBlock> findByEntityIdAndStatusIn(UUID entityId, java.util.Collection<HolderBlock.Status> statuses);

    List<HolderBlock> findByStatusInOrderByCreatedAtDesc(java.util.Collection<HolderBlock.Status> statuses);

    List<HolderBlock> findByAssetIdAndStatus(UUID assetId, HolderBlock.Status status);

    @Query("SELECT b FROM HolderBlock b WHERE b.status = 'ACTIVE' AND b.expiresAt IS NOT NULL AND b.expiresAt <= :now")
    List<HolderBlock> findExpiredActive(@Param("now") Instant now);

    /**
     * Records the first CONFIRMED on-chain freeze transaction of a block (H5). A targeted UPDATE, not an entity
     * save: the on-chain sync runs concurrently with a lift or an expiry transition, and a full-row save of a
     * stale entity would silently un-lift the block.
     *
     * @return 1 when the hash was stored, 0 when the block already carries one (or does not exist)
     */
    @Modifying
    @Query("UPDATE HolderBlock b SET b.onChainFreezeTxHash = :hash "
            + "WHERE b.id = :id AND b.onChainFreezeTxHash IS NULL")
    int recordOnChainFreezeTxHash(@Param("id") UUID id, @Param("hash") String hash);

    /** All blocks with the given status — used for the global compliance work-queue. */
    List<HolderBlock> findByStatusOrderByCreatedAtDesc(HolderBlock.Status status);
}
