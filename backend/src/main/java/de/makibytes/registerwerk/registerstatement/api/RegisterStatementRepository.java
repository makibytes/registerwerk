package de.makibytes.registerwerk.registerstatement.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RegisterStatementRepository extends JpaRepository<RegisterStatement, UUID> {

    Page<RegisterStatement> findByInvestorIdOrderByIssuedAtDesc(UUID investorId, Pageable pageable);

    List<RegisterStatement> findByHolderIdOrderByIssuedAtDesc(UUID holderId);

    /** Retry queue for the delivery worker. */
    List<RegisterStatement> findByDeliveryStatus(DeliveryStatus deliveryStatus);
}
