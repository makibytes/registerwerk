package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.enums.Jurisdiction;
import de.makibytes.registerwerk.domain.kyc.KycJurisdictionApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycJurisdictionApprovalRepository extends JpaRepository<KycJurisdictionApproval, UUID> {

    Optional<KycJurisdictionApproval> findByEntityIdAndJurisdiction(UUID entityId, Jurisdiction jurisdiction);

    List<KycJurisdictionApproval> findByEntityId(UUID entityId);
}
