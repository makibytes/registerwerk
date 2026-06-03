package de.makibytes.registerwerk.kyc.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HolderBlockRepository extends JpaRepository<HolderBlock, UUID> {

    List<HolderBlock> findByWalletAddressAndStatus(String walletAddress, HolderBlock.Status status);

    List<HolderBlock> findByEntityIdAndStatus(UUID entityId, HolderBlock.Status status);

    List<HolderBlock> findByAssetIdAndStatus(UUID assetId, HolderBlock.Status status);

    @Query("SELECT b FROM HolderBlock b WHERE b.status = 'ACTIVE' AND b.expiresAt IS NOT NULL AND b.expiresAt <= :now")
    List<HolderBlock> findExpiredActive(@Param("now") Instant now);

    /** All blocks with the given status — used for the global compliance work-queue. */
    List<HolderBlock> findByStatusOrderByCreatedAtDesc(HolderBlock.Status status);
}
