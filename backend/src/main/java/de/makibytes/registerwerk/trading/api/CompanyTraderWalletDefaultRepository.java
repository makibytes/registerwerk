package de.makibytes.registerwerk.trading.api;

import de.makibytes.registerwerk.trading.api.CompanyTraderWalletDefault;
import de.makibytes.registerwerk.trading.api.TradingAssetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyTraderWalletDefaultRepository extends JpaRepository<CompanyTraderWalletDefault, UUID> {

    List<CompanyTraderWalletDefault> findByLegalEntityId(UUID legalEntityId);

    Optional<CompanyTraderWalletDefault> findByLegalEntityIdAndAssetType(UUID legalEntityId, TradingAssetType assetType);
}
