package de.makibytes.registerwerk.kyc.api;

import de.makibytes.registerwerk.kyc.api.KycDocument;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
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
