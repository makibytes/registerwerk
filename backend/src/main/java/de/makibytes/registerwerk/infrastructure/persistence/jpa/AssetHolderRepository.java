package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.asset.AssetHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetHolderRepository extends JpaRepository<AssetHolder, UUID> {

    Page<AssetHolder> findByAssetId(UUID assetId, Pageable pageable);

    List<AssetHolder> findByInvestorId(UUID investorId);

    Page<AssetHolder> findByInvestorId(UUID investorId, Pageable pageable);

    Optional<AssetHolder> findByAssetIdAndWalletAddress(UUID assetId, String walletAddress);
}
