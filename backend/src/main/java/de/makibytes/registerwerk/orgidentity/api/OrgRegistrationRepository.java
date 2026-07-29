package de.makibytes.registerwerk.orgidentity.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgRegistrationRepository extends JpaRepository<OrgRegistration, UUID> {

    Optional<OrgRegistration> findByLegalEntityIdAndChainConfigId(UUID legalEntityId, UUID chainConfigId);

    List<OrgRegistration> findByLegalEntityId(UUID legalEntityId);

    Page<OrgRegistration> findByStatus(OrgRegistrationStatus status, Pageable pageable);

    List<OrgRegistration> findByStatus(OrgRegistrationStatus status);
}
