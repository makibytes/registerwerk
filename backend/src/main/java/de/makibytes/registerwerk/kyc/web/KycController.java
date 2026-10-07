package de.makibytes.registerwerk.kyc.web;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import de.makibytes.registerwerk.kyc.internal.DocumentService;
import de.makibytes.registerwerk.kyc.internal.KycService;
import de.makibytes.registerwerk.kyc.api.KycComplianceService;
import de.makibytes.registerwerk.kyc.api.KycDocument;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.kyc.api.KycJurisdictionApproval;
import de.makibytes.registerwerk.kyc.api.KycDocumentRepository;

import de.makibytes.registerwerk.kyc.web.dto.JurisdictionApprovalRequest;
import de.makibytes.registerwerk.kyc.web.dto.KycComplianceResponse;
import de.makibytes.registerwerk.kyc.web.dto.KycDocumentResponse;
import de.makibytes.registerwerk.kyc.web.dto.KycJurisdictionApprovalResponse;
import de.makibytes.registerwerk.kyc.web.dto.KycApprovalRequest;
import de.makibytes.registerwerk.kyc.web.dto.KycRejectionRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;

/**
 * REST controller for KYC document management and KYC status operations.
 */
@RestController
@RequestMapping("/api/v1/entities/{entityId}/kyc")
public class KycController {

    /**
     * Identity documents and UBO evidence are readable by the operator's compliance roles and by the
     * entity's own COMPANY_ADMIN, not by every user of the entity (6-07).
     */
    private static final String DOCUMENT_READ = "hasAnyRole('REGISTRY_ADMIN', 'AUDIT', 'COMPLIANCE_OFFICER') or "
            + "(hasRole('COMPANY_ADMIN') and @entityOwnershipChecker.isOwner(#entityId, authentication))";

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
     * {@code expiresAt} is required for identity and register evidence (passport, identity document,
     * commercial register and beneficial-owner register extracts) and may not be in the past;
     * {@code issueDate} (optional, not in the future) starts the "too old" clock of the checklist.
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or " +
            "(hasRole('COMPANY_ADMIN') and @entityOwnershipChecker.isOwner(#entityId, authentication))")
    public ResponseEntity<KycDocumentResponse> uploadDocument(
            @PathVariable UUID entityId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") KycDocument.DocumentType documentType,
            @RequestParam(value = "jurisdiction", required = false) Jurisdiction jurisdiction,
            @RequestParam(value = "issueDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDate,
            @RequestParam(value = "expiresAt", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresAt,
            Authentication auth) throws IOException {

        UUID uploadedBy = extractActorId(auth);
        KycDocument doc = documentService.storeDocument(
            entityId,
            file.getBytes(),
            file.getOriginalFilename(),
            file.getContentType(),
            documentType,
            uploadedBy,
            primaryRole(auth),
            jurisdiction,
            issueDate,
            expiresAt
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(KycDocumentResponse.from(doc));
    }

    /**
     * Lists all non-deleted KYC documents for the given entity.
     */
    @GetMapping("/documents")
    @PreAuthorize(DOCUMENT_READ)
    public ResponseEntity<List<KycDocumentResponse>> listDocuments(@PathVariable UUID entityId) {
        List<KycDocument> docs = kycDocumentRepository.findByLegalEntityIdAndDeletedAtIsNull(entityId);
        return ResponseEntity.ok(docs.stream().map(KycDocumentResponse::from).toList());
    }

    /**
     * Downloads the binary content of a KYC document.
     */
    @GetMapping("/documents/{docId}")
    @PreAuthorize(DOCUMENT_READ)
    public ResponseEntity<byte[]> downloadDocument(
            @PathVariable UUID entityId,
            @PathVariable UUID docId) {
        KycDocument doc = kycDocumentRepository.findByIdAndLegalEntityIdAndDeletedAtIsNull(docId, entityId)
            .orElseThrow(() -> new de.makibytes.registerwerk.shared.EntityNotFoundException("KycDocument", docId));
        byte[] content = documentService.retrieveContent(entityId, docId);
        return ResponseEntity.ok()
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(doc.getFileName() != null ? doc.getFileName() : "document", java.nio.charset.StandardCharsets.UTF_8)
                    .build().toString())
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
        documentService.softDeleteDocument(entityId, docId, extractActorId(auth), primaryRole(auth));
        return ResponseEntity.noContent().build();
    }

    /**
     * Approves KYC for the given entity. Runs the document checklist, beneficial-owner coverage, validity cap
     * and screening gates (6-15); {@code overrideNote} (REGISTRY_ADMIN) accepts an incomplete checklist or the
     * senior-managing-official fallback as a recorded risk acceptance.
     */
    @PostMapping("/approve")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
    @RequiresStepUp(requireSecondApprover = true, reason = "KYC_APPROVE")
    public ResponseEntity<Void> approveKyc(
            @PathVariable UUID entityId,
            @RequestBody(required = false) @Valid KycApprovalRequest body,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId,
            Authentication auth) {
        kycService.approveKyc(entityId,
            body != null ? body.expiryDate() : null,
            extractActorId(auth),
            approverId,
            body != null ? body.jurisdiction() : null,
            body != null ? body.overrideNote() : null,
            isRegistryAdmin(auth));
        return ResponseEntity.noContent().build();
    }

    /**
     * Rejects KYC for the given entity.
     */
    @PostMapping("/reject")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
    @RequiresStepUp(requireSecondApprover = true, reason = "KYC_REJECT")
    public ResponseEntity<Void> rejectKyc(
            @PathVariable UUID entityId,
            @RequestBody @Valid KycRejectionRequest body,
            Authentication auth) {
        kycService.rejectKyc(entityId, body.reason().trim(), body.customerReasonCode(), extractActorId(auth));
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
                .map(KycJurisdictionApprovalResponse::from).toList());
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
            .map(a -> ResponseEntity.ok(KycJurisdictionApprovalResponse.from(a)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Approves KYC for a specific jurisdiction.
     */
    @PostMapping("/jurisdictions/{jurisdiction}/approve")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
    @RequiresStepUp(requireSecondApprover = true, reason = "KYC_JURISDICTION_APPROVE")
    public ResponseEntity<KycJurisdictionApprovalResponse> approveJurisdiction(
            @PathVariable UUID entityId,
            @PathVariable Jurisdiction jurisdiction,
            @RequestBody(required = false) @Valid JurisdictionApprovalRequest body,
            Authentication auth) {
        LocalDate expiresAt = (body != null && body.expiresAt() != null)
            ? body.expiresAt()
            : LocalDate.now().plusYears(1);

        String overrideNote = body != null ? body.overrideNote() : null;

        KycComplianceService.ComplianceResult compliance =
            kycComplianceService.checkCompliance(entityId, jurisdiction);

        if (!compliance.fullyCompliant()) {
            if (overrideNote == null || overrideNote.isBlank()) {
                throw new IllegalArgumentException(
                    "KYC checklist is incomplete for jurisdiction " + jurisdiction.name()
                        + ". Add overrideNote to approve as risk acceptance.");
            }
            if (!isRegistryAdmin(auth)) {
                throw new AccessDeniedException(
                    "Only REGISTRY_ADMIN may approve non-compliant KYC with overrideNote.");
            }
        }

        KycJurisdictionApproval saved = kycService.approveKycForJurisdiction(
            entityId,
            jurisdiction,
            expiresAt,
            extractActorId(auth),
            overrideNote,
            compliance.missingCount(),
            compliance.expiredCount(),
            compliance.tooOldCount());
        return ResponseEntity.ok(KycJurisdictionApprovalResponse.from(saved));
    }

    /**
     * Rejects KYC for a specific jurisdiction.
     */
    @PostMapping("/jurisdictions/{jurisdiction}/reject")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
    @RequiresStepUp(requireSecondApprover = true, reason = "KYC_JURISDICTION_REJECT")
    public ResponseEntity<KycJurisdictionApprovalResponse> rejectJurisdiction(
            @PathVariable UUID entityId,
            @PathVariable Jurisdiction jurisdiction,
            @RequestBody @Valid KycRejectionRequest body,
            Authentication auth) {
        KycJurisdictionApproval saved = kycService.rejectKycForJurisdiction(
            entityId, jurisdiction, body.reason().trim(), extractActorId(auth));
        return ResponseEntity.ok(KycJurisdictionApprovalResponse.from(saved));
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
        return ResponseEntity.ok(KycComplianceResponse.from(result));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID extractActorId(Authentication auth) {
        if (auth == null) return null;
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String primaryRole(Authentication auth) {
        return SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN");
    }

    private boolean isRegistryAdmin(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_REGISTRY_ADMIN".equals(a.getAuthority()));
    }
}
