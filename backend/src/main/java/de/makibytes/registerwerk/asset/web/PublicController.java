package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.internal.TermSheetService;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.kyc.api.JurisdictionRequirementConfig;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.asset.api.AssetDocument;
import de.makibytes.registerwerk.asset.api.AssetDocumentType;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetDocumentRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.web.dto.AssetDocumentResponse;
import de.makibytes.registerwerk.kyc.web.dto.JurisdictionRequirementResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Public REST endpoints — no authentication required.
 * Returns only public termsheet data from assets.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicController {

    private final AssetRepository assetRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final AssetDocumentRepository assetDocumentRepository;
    private final TermSheetService termSheetService;
    private final JurisdictionRequirementConfig jurisdictionConfig;

    public PublicController(
            AssetRepository assetRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            AssetDocumentRepository assetDocumentRepository,
            TermSheetService termSheetService,
            JurisdictionRequirementConfig jurisdictionConfig) {
        this.assetRepository = assetRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.assetDocumentRepository = assetDocumentRepository;
        this.termSheetService = termSheetService;
        this.jurisdictionConfig = jurisdictionConfig;
    }

    /**
     * Returns the public data for an asset identified by ISIN.
     */
    @GetMapping("/assets/{isin}")
    public ResponseEntity<Map<String, Object>> getByIsin(@PathVariable String isin) {
        Asset asset = assetRepository.findByIsin(isin)
            .orElseThrow(() -> new EntityNotFoundException("Asset", "isin", isin));
        return ResponseEntity.ok(buildPublicAssetResponse(asset));
    }

    /**
     * Returns the public data for an asset identified by its contract address.
     */
    @GetMapping("/assets/by-address/{contractAddress}")
    public ResponseEntity<Map<String, Object>> getByContractAddress(@PathVariable String contractAddress) {
        AssetDeployment deployment = assetDeploymentRepository
            .findFirstByContractAddressIgnoreCase(contractAddress)
            .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", "contractAddress", contractAddress));

        Asset asset = assetRepository.findById(deployment.getAssetId())
            .orElseThrow(() -> new EntityNotFoundException("Asset", deployment.getAssetId()));

        return ResponseEntity.ok(buildPublicAssetResponse(asset));
    }

    /**
     * Returns term sheet metadata for an asset identified by ISIN (no auth required).
     * Enables BaFin and other regulators to discover the term sheet by ISIN.
     */
    @GetMapping("/assets/{isin}/termsheet")
    public ResponseEntity<AssetDocumentResponse> getTermSheetByIsin(@PathVariable String isin) {
        Asset asset = assetRepository.findByIsin(isin)
            .orElseThrow(() -> new EntityNotFoundException("Asset", "isin", isin));
        AssetDocument doc = firstTermSheet(asset);
        return ResponseEntity.ok(toDocResponse(doc));
    }

    /**
     * Downloads the term sheet content for an asset identified by ISIN (no auth required).
     * Enables BaFin direct access to the regulatory document.
     */
    @GetMapping("/assets/{isin}/termsheet/content")
    public ResponseEntity<byte[]> downloadTermSheetByIsin(@PathVariable String isin) {
        Asset asset = assetRepository.findByIsin(isin)
            .orElseThrow(() -> new EntityNotFoundException("Asset", "isin", isin));
        AssetDocument doc = firstTermSheet(asset);
        byte[] content = termSheetService.getDocumentContent(doc.getId());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(doc.getFileName() != null ? doc.getFileName() : "term_sheet")
                    .build().toString())
            .contentType(MediaType.parseMediaType(doc.getMimeType()))
            .body(content);
    }

    // ── Jurisdiction requirement catalogue (public, no auth) ──────────────────

    /**
     * Returns KYC document requirements for all supported jurisdictions.
     * Allows the frontend to show users exactly what documents they need before uploading.
     */
    @GetMapping("/jurisdictions")
    public ResponseEntity<List<JurisdictionRequirementResponse>> listJurisdictions() {
        return ResponseEntity.ok(
            jurisdictionConfig.getAllProfiles().stream()
                .map(this::toJurisdictionResponse).toList()
        );
    }

    /**
     * Returns KYC document requirements for a single jurisdiction.
     */
    @GetMapping("/jurisdictions/{jurisdiction}")
    public ResponseEntity<JurisdictionRequirementResponse> getJurisdiction(
            @PathVariable Jurisdiction jurisdiction) {
        return ResponseEntity.ok(toJurisdictionResponse(jurisdictionConfig.getProfile(jurisdiction)));
    }

    private JurisdictionRequirementResponse toJurisdictionResponse(
            JurisdictionRequirementConfig.JurisdictionProfile profile) {
        var reqs = profile.issuerRequirements().stream().map(r ->
            new JurisdictionRequirementResponse.DocumentRequirementResponse(
                r.documentType().name(),
                r.mandatory(),
                r.localName(),
                r.description(),
                r.maxAge() != null ? r.maxAge().toDays() : null
            )
        ).toList();
        return new JurisdictionRequirementResponse(
            profile.jurisdiction().name(),
            profile.jurisdiction().displayName,
            profile.jurisdiction().regulator,
            profile.jurisdiction().applicableLaw,
            reqs
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AssetDocument firstTermSheet(Asset asset) {
        List<AssetDocument> docs = assetDocumentRepository
            .findByAssetIdAndDocumentTypeAndDeletedAtIsNull(asset.getId(), AssetDocumentType.TERM_SHEET);
        if (docs.isEmpty()) {
            throw new EntityNotFoundException("TermSheet for Asset", asset.getId());
        }
        return docs.get(0);
    }

    private AssetDocumentResponse toDocResponse(AssetDocument doc) {
        return new AssetDocumentResponse(
            doc.getId(), doc.getAssetId(), doc.getDocumentType().name(),
            doc.getSource().name(), doc.getMimeType(), doc.getFileName(),
            doc.getSizeBytes(), doc.getContentHash(),
            doc.getChain() != null ? doc.getChain().name() : null,
            doc.getNetwork() != null ? doc.getNetwork().name() : null,
            doc.getOnchainUri(), doc.getUploadedAt(), doc.getFetchedAt(), doc.hasContent());
    }



    private Map<String, Object> buildPublicAssetResponse(Asset asset) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", asset.getId());
        response.put("assetNumber", asset.getAssetNumber());
        response.put("name", asset.getName());
        response.put("isin", asset.getIsin());
        response.put("tokenStandard", asset.getTokenStandard());
        response.put("onchainLevel", asset.getOnchainLevel());
        response.put("status", asset.getStatus());
        response.put("publicData", asset.getPublicData());
        return response;
    }
}
