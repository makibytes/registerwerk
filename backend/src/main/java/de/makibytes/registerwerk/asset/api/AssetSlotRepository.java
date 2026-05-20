package de.makibytes.registerwerk.asset.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetSlotRepository extends JpaRepository<AssetSlot, UUID> {

    List<AssetSlot> findByAssetId(UUID assetId);

    Optional<AssetSlot> findByAssetIdAndSlotId(UUID assetId, BigInteger slotId);
}
