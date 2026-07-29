package de.makibytes.registerwerk.lending.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LendingSupplyPositionRepository extends JpaRepository<LendingSupplyPosition, UUID> {

    Optional<LendingSupplyPosition> findByMarketIdAndWalletAddressIgnoreCase(UUID marketId, String walletAddress);

    List<LendingSupplyPosition> findByWalletAddressIgnoreCase(String walletAddress);

    List<LendingSupplyPosition> findByMarketId(UUID marketId);
}
