package de.makibytes.registerwerk.asset.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvestorLimitRepository extends JpaRepository<InvestorLimit, UUID> {

    Optional<InvestorLimit> findByAssetIdAndInvestorEntityId(UUID assetId, UUID investorEntityId);

    List<InvestorLimit> findByAssetIdOrderByUpdatedAtDesc(UUID assetId);
}
