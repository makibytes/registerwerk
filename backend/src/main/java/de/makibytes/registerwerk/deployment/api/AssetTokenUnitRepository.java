package de.makibytes.registerwerk.deployment.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetTokenUnitRepository extends JpaRepository<AssetTokenUnit, UUID> {

    List<AssetTokenUnit> findByAssetIdAndSlotId(UUID assetId, BigInteger slotId);

    Optional<AssetTokenUnit> findByAssetIdAndTokenId(UUID assetId, BigInteger tokenId);
}
