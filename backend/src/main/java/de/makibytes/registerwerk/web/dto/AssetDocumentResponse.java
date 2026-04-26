package de.makibytes.registerwerk.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for an asset document (term sheet, prospectus, etc.).
 */
public record AssetDocumentResponse(
    UUID id,
    UUID assetId,
    String documentType,
    String source,
    String mimeType,
    String fileName,
    Long sizeBytes,
    String contentHash,
    String chain,
    String network,
    String onchainUri,
    Instant uploadedAt,
    Instant fetchedAt,
    boolean contentAvailable
) {}
