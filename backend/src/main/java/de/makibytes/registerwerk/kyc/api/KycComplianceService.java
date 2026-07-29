package de.makibytes.registerwerk.kyc.api;

import de.makibytes.registerwerk.kyc.api.KycDocument;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.kyc.api.KycDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Checks whether a legal entity has the required KYC documents
 * for a given regulatory jurisdiction.
 *
 * Documents without a jurisdiction tag (null) are treated as matching the configured
 * document rule across all jurisdictions. This is a workflow rule, not a legal conclusion.
 */
@Service
@Transactional(readOnly = true)
public class KycComplianceService implements de.makibytes.registerwerk.kyc.KycApi {

    private final KycDocumentRepository kycDocumentRepository;
    private final JurisdictionRequirementConfig requirementConfig;

    public KycComplianceService(
            KycDocumentRepository kycDocumentRepository,
            JurisdictionRequirementConfig requirementConfig) {
        this.kycDocumentRepository = kycDocumentRepository;
        this.requirementConfig = requirementConfig;
    }

    public record DocumentStatus(
        KycDocument.DocumentType documentType,
        boolean mandatory,
        String localName,
        String description,
        boolean present,
        boolean expired,
        boolean tooOld,
        Instant documentDate,
        UUID documentId
    ) {}

    public record ComplianceResult(
        Jurisdiction jurisdiction,
        UUID entityId,
        List<DocumentStatus> documents,
        boolean fullyCompliant,
        int missingCount,
        int expiredCount,
        int tooOldCount
    ) {}

    /**
     * Checks KYC document compliance for the given entity against a jurisdiction's requirements.
     *
     * @param entityId     legal entity to check
     * @param jurisdiction target jurisdiction
     * @return detailed compliance result with per-document status
     */
    public ComplianceResult checkCompliance(UUID entityId, Jurisdiction jurisdiction) {
        JurisdictionRequirementConfig.JurisdictionProfile profile =
            requirementConfig.getProfile(jurisdiction);

        List<KycDocument> allDocs = kycDocumentRepository.findByLegalEntityIdAndDeletedAtIsNull(entityId);

        List<DocumentStatus> statuses = new ArrayList<>();
        int missing = 0, expired = 0, tooOld = 0;

        for (JurisdictionRequirementConfig.DocumentRequirement req : profile.issuerRequirements()) {
            // Find the newest non-deleted doc of this type that matches jurisdiction scope
            KycDocument best = allDocs.stream()
                .filter(d -> d.getDocumentType() == req.documentType())
                .filter(d -> d.getJurisdiction() == null || d.getJurisdiction() == jurisdiction)
                .max(Comparator.comparing(KycDocument::getUploadedAt))
                .orElse(null);

            boolean present = best != null;
            boolean isExpired = present
                && best.getExpiresAt() != null
                && best.getExpiresAt().isBefore(LocalDate.now());
            boolean isTooOld = present && req.maxAge() != null
                && best.getUploadedAt().isBefore(Instant.now().minus(req.maxAge()));

            if (req.mandatory()) {
                if (!present) missing++;
                else if (isExpired) expired++;
                else if (isTooOld) tooOld++;
            }

            statuses.add(new DocumentStatus(
                req.documentType(),
                req.mandatory(),
                req.localName(),
                req.description(),
                present,
                isExpired,
                isTooOld,
                present ? best.getUploadedAt() : null,
                present ? best.getId() : null
            ));
        }

        boolean fullyCompliant = missing == 0 && expired == 0 && tooOld == 0;
        return new ComplianceResult(jurisdiction, entityId, statuses, fullyCompliant, missing, expired, tooOld);
    }
}
