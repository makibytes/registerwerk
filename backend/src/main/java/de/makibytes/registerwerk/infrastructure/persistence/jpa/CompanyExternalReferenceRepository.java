package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.customer.CompanyExternalReference;
import de.makibytes.registerwerk.domain.enums.ExternalReferenceSubjectType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyExternalReferenceRepository extends JpaRepository<CompanyExternalReference, UUID> {

    Optional<CompanyExternalReference> findByOwnerLegalEntityIdAndSubjectTypeAndSubjectId(
            UUID ownerLegalEntityId,
            ExternalReferenceSubjectType subjectType,
            UUID subjectId);

    List<CompanyExternalReference> findByOwnerLegalEntityIdOrderByUpdatedAtDesc(UUID ownerLegalEntityId);

    List<CompanyExternalReference> findByOwnerLegalEntityIdAndSubjectTypeOrderByUpdatedAtDesc(
            UUID ownerLegalEntityId,
            ExternalReferenceSubjectType subjectType);

    List<CompanyExternalReference> findByOwnerLegalEntityIdAndExternalIdOrderByUpdatedAtDesc(
            UUID ownerLegalEntityId,
            String externalId);

    List<CompanyExternalReference> findByOwnerLegalEntityIdAndSubjectTypeAndExternalIdOrderByUpdatedAtDesc(
            UUID ownerLegalEntityId,
            ExternalReferenceSubjectType subjectType,
            String externalId);

    List<CompanyExternalReference> findByOwnerLegalEntityIdAndSubjectTypeAndSubjectIdIn(
            UUID ownerLegalEntityId,
            ExternalReferenceSubjectType subjectType,
            Collection<UUID> subjectIds);
}
