package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.entity.KycDocument;

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
    LocalDate expiresAt
) {}
