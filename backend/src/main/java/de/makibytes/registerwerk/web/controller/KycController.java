package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.customer.DocumentService;
import de.makibytes.registerwerk.application.customer.KycService;
import de.makibytes.registerwerk.application.kyc.KycComplianceService;
import de.makibytes.registerwerk.domain.entity.KycDocument;
import de.makibytes.registerwerk.domain.enums.Jurisdiction;
import de.makibytes.registerwerk.domain.enums.KycStatus;
import de.makibytes.registerwerk.domain.kyc.KycJurisdictionApproval;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.KycDocumentRepository;
import de.makibytes.registerwerk.web.dto.DocumentStatusResponse;
import de.makibytes.registerwerk.web.dto.KycComplianceResponse;
import de.makibytes.registerwerk.web.dto.KycDocumentResponse;
import de.makibytes.registerwerk.web.dto.KycJurisdictionApprovalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for KYC document management and KYC status operations.
 */
@RestController
@RequestMapping("/api/v1/entities/{entityId}/kyc")
public class KycController {

    private static final Logger log = LoggerFactory.getLogger(KycController.class);

    private final DocumentService documentService;
    private final KycService kycService;
    private final KycDocumentRepository kycDocumentRepository;
    private final KycComplianceService kycComplianceService;

    public KycController(
            DocumentService documentService,
            KycService kycService,
            KycDocumentRepository kycDocumentRepository,
            KycComplianceService kycComplianceService) {
        this.documentService = documentService;
        this.kycService = kycService;
        this.kycDocumentRepository = kycDocumentRepository;
        this.kycComplianceService = kycComplianceService;
    }

    /**
     * Uploads a KYC document for the given entity.
     * The optional {@code jurisdiction} parameter scopes the document to a specific
     * regulatory jurisdiction. Omitting it makes the document universal (satisfies any jurisdiction).
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPANY_ADMIN')")
    public ResponseEntity<KycDocumentResponse> uploadDocument(
            @PathVariable UUID entityId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") KycDocument.DocumentType documentType,
            @RequestParam(value = "jurisdiction", required = false) Jurisdiction jurisdiction,
            Authentication auth) throws IOException {

        UUID uploadedBy = extractActorId(auth);
        KycDocument doc = documentService.storeDocument(
            entityId,
            file.getBytes(),
            file.getOriginalFilename(),
            file.getContentType(),
            documentType,
            uploadedBy
        );
        if (jurisdiction != null) {
            doc.setJurisdiction(jurisdiction);
            doc = kycDocumentRepository.save(doc);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(doc));
    }

    /**
     * Lists all non-deleted KYC documents for the given entity.
     */
    @GetMapping("/documents")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @entityOwnershipChecker.isOwner(#entityId, authentication)")
    public ResponseEntity<List<KycDocumentResponse>> listDocuments(@PathVariable UUID entityId) {
        List<KycDocument> docs = kycDocumentRepository.findByLegalEntityIdAndDeletedAtIsNull(entityId);
        return ResponseEntity.ok(docs.stream().map(this::toResponse).toList());
    }

    /**
     * Downloads the binary content of a KYC document.
     */
    @GetMapping("/documents/{docId}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @entityOwnershipChecker.isOwner(#entityId, authentication)")
    public ResponseEntity<byte[]> downloadDocument(
            @PathVariable UUID entityId,
            @PathVariable UUID docId) {
        KycDocument doc = kycDocumentRepository.findById(docId)
            .orElseThrow(() -> new de.makibytes.registerwerk.application.exception.EntityNotFoundException("KycDocument", docId));
        byte[] content = documentService.retrieveContent(docId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.getFileName() + "\"")
            .contentType(MediaType.parseMediaType(doc.getMimeType()))
            .body(content);
    }

    /**
     * Soft-deletes a KYC document.
     */
    @DeleteMapping("/documents/{docId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> softDeleteDocument(
            @PathVariable UUID entityId,
            @PathVariable UUID docId,
            Authentication auth) {
        documentService.softDeleteDocument(docId, extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /**
     * Approves KYC for the given entity.
     */
    @PostMapping("/approve")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> approveKyc(
            @PathVariable UUID entityId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        LocalDate expiryDate = body.containsKey("expiryDate")
            ? LocalDate.parse(body.get("expiryDate"))
            : LocalDate.now().plusYears(1);
        kycService.approveKyc(entityId, expiryDate, extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /**
     * Rejects KYC for the given entity.
     */
    @PostMapping("/reject")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> rejectKyc(
            @PathVariable UUID entityId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        kycService.rejectKyc(entityId, body.getOrDefault("reason", ""), extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the current KYC status for the given entity.
     */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @entityOwnershipChecker.isOwner(#entityId, authentication)")
    public ResponseEntity<Map<String, KycStatus>> getKycStatus(@PathVariable UUID entityId) {
        return ResponseEntity.ok(Map.of("kycStatus", kycService.getKycStatus(entityId)));
    }

    // ── Per-jurisdiction KYC endpoints ────────────────────────────────────────

    /**
     * Lists all per-jurisdiction KYC approval records for the entity.
     */
    @GetMapping("/jurisdictions")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @entityOwnershipChecker.isOwner(#entityId, authentication)")
    public ResponseEntity<List<KycJurisdictionApprovalResponse>> listJurisdictionApprovals(
            @PathVariable UUID entityId) {
        return ResponseEntity.ok(
            kycService.getJurisdictionApprovals(entityId).stream()
                .map(this::toJurisdictionApprovalResponse).toList());
    }

    /**
     * Returns the KYC approval status for a single jurisdiction.
     */
    @GetMapping("/jurisdictions/{jurisdiction}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @entityOwnershipChecker.isOwner(#entityId, authentication)")
    public ResponseEntity<KycJurisdictionApprovalResponse> getJurisdictionApproval(
            @PathVariable UUID entityId,
            @PathVariable Jurisdiction jurisdiction) {
        return kycService.getJurisdictionApproval(entityId, jurisdiction)
            .map(a -> ResponseEntity.ok(toJurisdictionApprovalResponse(a)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Approves KYC for a specific jurisdiction.
     */
    @PostMapping("/jurisdictions/{jurisdiction}/approve")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<KycJurisdictionApprovalResponse> approveJurisdiction(
            @PathVariable UUID entityId,
            @PathVariable Jurisdiction jurisdiction,
            @RequestBody(required = false) Map<String, String> body,
            Authentication auth) {
        LocalDate expiresAt = (body != null && body.containsKey("expiresAt"))
            ? LocalDate.parse(body.get("expiresAt"))
            : LocalDate.now().plusYears(1);
        KycJurisdictionApproval saved = kycService.approveKycForJurisdiction(
            entityId, jurisdiction, expiresAt, extractActorId(auth));
        return ResponseEntity.ok(toJurisdictionApprovalResponse(saved));
    }

    /**
     * Rejects KYC for a specific jurisdiction.
     */
    @PostMapping("/jurisdictions/{jurisdiction}/reject")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<KycJurisdictionApprovalResponse> rejectJurisdiction(
            @PathVariable UUID entityId,
            @PathVariable Jurisdiction jurisdiction,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        KycJurisdictionApproval saved = kycService.rejectKycForJurisdiction(
            entityId, jurisdiction, body.getOrDefault("reason", ""), extractActorId(auth));
        return ResponseEntity.ok(toJurisdictionApprovalResponse(saved));
    }

    /**
     * Returns the full KYC compliance checklist for an entity against a jurisdiction's requirements.
     */
    @GetMapping("/compliance/{jurisdiction}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @entityOwnershipChecker.isOwner(#entityId, authentication)")
    public ResponseEntity<KycComplianceResponse> getCompliance(
            @PathVariable UUID entityId,
            @PathVariable Jurisdiction jurisdiction) {
        KycComplianceService.ComplianceResult result =
            kycComplianceService.checkCompliance(entityId, jurisdiction);
        return ResponseEntity.ok(toComplianceResponse(result));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private KycJurisdictionApprovalResponse toJurisdictionApprovalResponse(KycJurisdictionApproval a) {
        return new KycJurisdictionApprovalResponse(
            a.getId(), a.getEntityId(), a.getJurisdiction().name(),
            a.getJurisdiction().displayName, a.getStatus().name(),
            a.getApprovedBy(), a.getApprovedAt(), a.getExpiresAt(),
            a.getRejectionReason(), a.getOverrideNote());
    }

    private KycComplianceResponse toComplianceResponse(KycComplianceService.ComplianceResult r) {
        var docs = r.documents().stream().map(d -> new DocumentStatusResponse(
            d.documentType().name(), d.mandatory(), d.localName(), d.description(),
            d.present(), d.expired(), d.tooOld(), d.documentDate(), d.documentId()
        )).toList();
        return new KycComplianceResponse(
            r.jurisdiction().name(), r.jurisdiction().displayName,
            r.entityId(), docs, r.fullyCompliant(), r.missingCount(), r.expiredCount(), r.tooOldCount()
        );
    }

    private KycDocumentResponse toResponse(KycDocument doc) {
        return new KycDocumentResponse(
            doc.getId(),
            doc.getDocumentType(),
            doc.getFileName(),
            doc.getMimeType(),
            doc.getSizeBytes(),
            doc.getContentHash(),
            doc.getUploadedAt(),
            doc.getExpiresAt()
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
