package de.makibytes.registerwerk.kyc.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EddApprovalRepository extends JpaRepository<EddApproval, UUID> {

    List<EddApproval> findByNaturalPersonIdOrderByCreatedAtDesc(UUID naturalPersonId);

    /** Approvals of the given persons that are still in force at {@code now}. */
    List<EddApproval> findByNaturalPersonIdInAndReviewDueAfter(Collection<UUID> naturalPersonIds, Instant now);
}
