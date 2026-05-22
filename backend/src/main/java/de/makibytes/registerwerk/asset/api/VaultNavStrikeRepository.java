package de.makibytes.registerwerk.asset.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VaultNavStrikeRepository extends JpaRepository<VaultNavStrike, UUID> {

    List<VaultNavStrike> findByAssetIdOrderByEffectiveAtDesc(UUID assetId);
}
