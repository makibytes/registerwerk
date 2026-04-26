package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.entity.KycDocument;
import de.makibytes.registerwerk.domain.enums.Jurisdiction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KycDocumentRepository extends JpaRepository<KycDocument, UUID> {

    List<KycDocument> findByLegalEntityIdAndDeletedAtIsNull(UUID entityId);

    List<KycDocument> findByLegalEntityIdAndDocumentTypeAndDeletedAtIsNull(
        UUID entityId, KycDocument.DocumentType type);

    List<KycDocument> findByLegalEntityIdAndJurisdictionAndDeletedAtIsNull(
        UUID entityId, Jurisdiction jurisdiction);
}
