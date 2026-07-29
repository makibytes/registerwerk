package de.makibytes.registerwerk.registertransfer.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortfolioMigrationRequestRepository extends JpaRepository<PortfolioMigrationRequest, UUID> {

    List<PortfolioMigrationRequest> findByInvestorEntityIdOrderByInitiatedAtDesc(UUID investorEntityId);

    Optional<PortfolioMigrationRequest> findByHolderIdAndStatusNotIn(UUID holderId, List<TransferStatus> statuses);

    boolean existsByHolderIdAndStatusNotIn(UUID holderId, List<TransferStatus> statuses);
}
