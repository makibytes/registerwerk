package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.api.KycDocument;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response DTO for KYC document metadata.
 */
public record KycDocumentResponse(
    UUID id,
    KycDocument.DocumentType documentType,
    String fileName,
    String mimeType,
    Long sizeBytes,
    String contentHash,
    Instant uploadedAt,
    LocalDate expiresAt,
    LocalDate issueDate
) {
    public static KycDocumentResponse from(KycDocument doc) {
        return new KycDocumentResponse(doc.getId(), doc.getDocumentType(), doc.getFileName(), doc.getMimeType(),
            doc.getSizeBytes(), doc.getContentHash(), doc.getUploadedAt(), doc.getExpiresAt(), doc.getIssueDate());
    }
}
