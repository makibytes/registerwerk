package de.makibytes.registerwerk.kyc.api;

import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.kyc.api.KycJurisdictionApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycJurisdictionApprovalRepository extends JpaRepository<KycJurisdictionApproval, UUID> {

    Optional<KycJurisdictionApproval> findByEntityIdAndJurisdiction(UUID entityId, Jurisdiction jurisdiction);

    List<KycJurisdictionApproval> findByEntityId(UUID entityId);
}
