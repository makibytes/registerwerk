package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.internal.TermSheetService;
import de.makibytes.registerwerk.asset.api.AssetDocument;
import de.makibytes.registerwerk.asset.api.AssetDocumentType;
import de.makibytes.registerwerk.asset.web.dto.AssetDocumentResponse;
import de.makibytes.registerwerk.asset.web.dto.TermSheetSyncRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for asset documents (term sheets, prospectuses, etc.).
 * Supports direct upload and on-chain retrieval via ERC-1643.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/documents")
public class AssetDocumentController {

    private final TermSheetService termSheetService;

    public AssetDocumentController(TermSheetService termSheetService) {
        this.termSheetService = termSheetService;
    }

    /**
     * Uploads a document and attaches it to an asset.
     * Accepted formats: PDF, HTML, plain text, Markdown, JSON, XML, DOCX.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<AssetDocumentResponse> uploadDocument(
            @PathVariable UUID assetId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentType", defaultValue = "TERM_SHEET") AssetDocumentType documentType,
            Authentication auth) throws IOException {

        AssetDocument doc = termSheetService.uploadDocument(
            assetId,
            file.getBytes(),
            file.getOriginalFilename(),
            file.getContentType(),
            documentType,
            extractActorId(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(doc));
    }

    /**
     * Lists all non-deleted documents for an asset.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canRead(#assetId, authentication)")
    public ResponseEntity<List<AssetDocumentResponse>> listDocuments(@PathVariable UUID assetId) {
        return ResponseEntity.ok(
            termSheetService.listDocuments(assetId).stream().map(this::toResponse).toList());
    }

    /**
     * Returns metadata for a single document.
     */
    @GetMapping("/{docId}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canRead(#assetId, authentication)")
    public ResponseEntity<AssetDocumentResponse> getDocument(
            @PathVariable UUID assetId,
            @PathVariable UUID docId) {
        return ResponseEntity.ok(toResponse(termSheetService.getDocument(assetId, docId)));
    }

    /**
     * Downloads the binary content of a document.
     */
    @GetMapping("/{docId}/content")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canRead(#assetId, authentication)")
    public ResponseEntity<byte[]> downloadContent(
            @PathVariable UUID assetId,
            @PathVariable UUID docId) {
        AssetDocument doc = termSheetService.getDocument(assetId, docId);
        byte[] content = termSheetService.getDocumentContent(assetId, docId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(doc.getFileName() != null ? doc.getFileName() : "document", java.nio.charset.StandardCharsets.UTF_8)
                    .build().toString())
            .contentType(MediaType.parseMediaType(doc.getMimeType()))
            .body(content);
    }

    /**
     * Soft-deletes a document.
     */
    @DeleteMapping("/{docId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable UUID assetId,
            @PathVariable UUID docId,
            Authentication auth) {
        termSheetService.softDeleteDocument(assetId, docId, extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /**
     * Triggers an on-chain fetch for the term sheet.
     * The backend reads the document URI and hash from the deployed contract,
     * downloads the content, and stores it in the database.
     */
    @PostMapping("/sync-from-chain")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<AssetDocumentResponse> syncFromChain(
            @PathVariable UUID assetId,
            @RequestBody @Valid TermSheetSyncRequest request) {
        AssetDocument doc = termSheetService.syncFromChain(assetId, request.deploymentId());
        return ResponseEntity.ok(toResponse(doc));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AssetDocumentResponse toResponse(AssetDocument doc) {
        return new AssetDocumentResponse(
            doc.getId(),
            doc.getAssetId(),
            doc.getDocumentType().name(),
            doc.getSource().name(),
            doc.getMimeType(),
            doc.getFileName(),
            doc.getSizeBytes(),
            doc.getContentHash(),
            doc.getChain() != null ? doc.getChain().name() : null,
            doc.getNetwork() != null ? doc.getNetwork().name() : null,
            doc.getOnchainUri(),
            doc.getUploadedAt(),
            doc.getFetchedAt(),
            doc.hasContent()
        );
    }

    private UUID extractActorId(Authentication auth) {
        if (auth == null) return null;
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
