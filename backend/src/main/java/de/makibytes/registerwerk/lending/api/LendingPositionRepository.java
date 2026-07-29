package de.makibytes.registerwerk.lending.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LendingPositionRepository extends JpaRepository<LendingPosition, UUID> {

    Optional<LendingPosition> findByMarketIdAndWalletAddressIgnoreCase(UUID marketId, String walletAddress);

    List<LendingPosition> findByWalletAddressIgnoreCaseAndStatus(String walletAddress, LendingPositionStatus status);

    List<LendingPosition> findByWalletAddressIgnoreCase(String walletAddress);

    List<LendingPosition> findByMarketId(UUID marketId);
}
