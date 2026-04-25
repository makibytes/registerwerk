package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.domain.asset.AssetDeployment;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetDeploymentRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public PublicController(
            AssetRepository assetRepository,
            AssetDeploymentRepository assetDeploymentRepository) {
        this.assetRepository = assetRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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
