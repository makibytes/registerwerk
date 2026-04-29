package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.asset.AssetHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetHolderRepository extends JpaRepository<AssetHolder, UUID> {

    Page<AssetHolder> findByAssetId(UUID assetId, Pageable pageable);

    List<AssetHolder> findByInvestorId(UUID investorId);

    Page<AssetHolder> findByInvestorId(UUID investorId, Pageable pageable);

    Optional<AssetHolder> findByAssetIdAndWalletAddress(UUID assetId, String walletAddress);

    List<AssetHolder> findByWalletAddressIn(Collection<String> walletAddresses);

    @Query("SELECT h FROM AssetHolder h WHERE h.assetId IN :assetIds AND h.walletAddress IN :addresses")
    List<AssetHolder> findByAssetIdInAndWalletAddressIn(
            @Param("assetIds") Collection<UUID> assetIds,
            @Param("addresses") Collection<String> addresses);

    @Query("SELECT DISTINCT h.assetId FROM AssetHolder h WHERE h.investorId = :investorId")
    List<UUID> findDistinctAssetIdsByInvestorId(@Param("investorId") UUID investorId);
}
