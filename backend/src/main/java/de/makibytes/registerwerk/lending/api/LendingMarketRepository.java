package de.makibytes.registerwerk.lending.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LendingMarketRepository extends JpaRepository<LendingMarket, UUID> {

    List<LendingMarket> findByStatus(LendingMarketStatus status);

    Optional<LendingMarket> findByChainConfigIdAndMarketAddressIgnoreCase(UUID chainConfigId, String marketAddress);

    boolean existsByChainConfigIdAndMarketAddressIgnoreCase(UUID chainConfigId, String marketAddress);

    /** Used by {@code ForcedTransferReconciliationListener} to detect a forced-transfer/force-burn
     *  whose {@code from} address is a known lending market's own collateral custody address. */
    Optional<LendingMarket> findByMarketAddressIgnoreCase(String marketAddress);

    List<LendingMarket> findByCollateralAssetId(UUID collateralAssetId);
}
