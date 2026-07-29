package de.makibytes.registerwerk.registertransfer.web;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.registertransfer.api.InspectionLegalBasis;
import de.makibytes.registerwerk.registertransfer.api.RegisterInspectionRequest;
import de.makibytes.registerwerk.registertransfer.internal.RegisterInspectionService;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Self-service § 10 eWpG register-extract download for an issuer's own asset. An
 * ISSUER is always a Berechtigter under § 10(2) eWpRV — {@link RegisterInspectionService#submit}
 * auto-approves that basis — so this shortcuts straight to submit-then-fulfil instead of
 * routing through the operator review queue a LEGITIMATE_INTEREST applicant needs.
 * Mirrors the investor-facing self-service pattern in
 * {@code registerstatement.web.MeRegisterDocumentController}.
 */
@RestController
@RequestMapping("/api/v1/me/assets")
@PreAuthorize("isAuthenticated()")
public class MyAssetRegisterExtractController {

    private final AssetRepository assetRepository;
    private final LegalEntityRepository entityRepository;
    private final RegisterInspectionService inspectionService;

    MyAssetRegisterExtractController(AssetRepository assetRepository, LegalEntityRepository entityRepository,
                                     RegisterInspectionService inspectionService) {
        this.assetRepository = assetRepository;
        this.entityRepository = entityRepository;
        this.inspectionService = inspectionService;
    }

    @GetMapping("/{assetId}/register-extract")
    public ResponseEntity<byte[]> downloadRegisterExtract(@PathVariable UUID assetId, Authentication authentication) {
        UUID entityId = SecurityUtils.extractEntityId(authentication);
        if (entityId == null) {
            return ResponseEntity.status(403).build();
        }
        Asset asset = assetRepository.findById(assetId).orElse(null);
        if (asset == null) {
            return ResponseEntity.notFound().build();
        }
        // The self-service shortcut only ever speaks for the caller's own issuance —
        // anyone else must go through the LEGITIMATE_INTEREST review queue instead.
        if (!entityId.equals(asset.getIssuerId())) {
            return ResponseEntity.status(403).build();
        }

        String requesterName = entityRepository.findById(entityId)
                .map(LegalEntity::getCurrentName)
                .orElse("Issuer");
        RegisterInspectionRequest request = inspectionService.submit(
                assetId, entityId, requesterName, null,
                InspectionLegalBasis.ISSUER, "Issuer self-service §10 eWpG register extract download");
        byte[] pdf = inspectionService.fulfil(request.getId());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Registereinsicht.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
