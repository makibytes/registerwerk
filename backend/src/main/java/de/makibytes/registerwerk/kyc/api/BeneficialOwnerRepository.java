package de.makibytes.registerwerk.kyc.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BeneficialOwnerRepository extends JpaRepository<BeneficialOwner, UUID> {
    List<BeneficialOwner> findByEntityIdAndCeasedAtIsNull(UUID entityId);
    List<BeneficialOwner> findByNaturalPersonId(UUID naturalPersonId);

    /** Every currently-active (non-ceased) beneficial-owner link — periodic re-screening scope. */
    List<BeneficialOwner> findByCeasedAtIsNull();
}
