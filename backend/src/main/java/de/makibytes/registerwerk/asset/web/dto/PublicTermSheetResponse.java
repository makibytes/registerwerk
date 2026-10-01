package de.makibytes.registerwerk.asset.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Public term sheet metadata served by ISIN (6-34). Same fields as {@link AssetDocumentResponse} plus the
 * 1-based {@code version} (order of upload); {@code contentHash} lets a reader verify the downloaded bytes.
 */
public record PublicTermSheetResponse(
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
    boolean contentAvailable,
    int version
) {}
