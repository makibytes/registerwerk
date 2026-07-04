package de.makibytes.registerwerk.customer.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ErasureRequestRepository extends JpaRepository<ErasureRequest, UUID> {

    /** An existing open request for the entity, so repeated clicks don't stack duplicates. */
    Optional<ErasureRequest> findFirstByEntityIdAndStatusInOrderByRequestedAtAsc(
            UUID entityId, List<ErasureRequestStatus> statuses);

    List<ErasureRequest> findByStatusInOrderByRequestedAtAsc(List<ErasureRequestStatus> statuses);
}
