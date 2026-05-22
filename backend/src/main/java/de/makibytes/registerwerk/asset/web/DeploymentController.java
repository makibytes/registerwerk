package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.internal.AssetDeploymentService;
import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.asset.web.dto.DeploymentCreateRequest;
import de.makibytes.registerwerk.asset.web.dto.DeploymentResponse;
import de.makibytes.registerwerk.asset.web.DeploymentMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for managing on-chain asset deployments.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/deployments")
public class DeploymentController {

    private static final Logger log = LoggerFactory.getLogger(DeploymentController.class);

    private final AssetDeploymentService assetDeploymentService;
    private final DeploymentMapper deploymentMapper;

    public DeploymentController(
            AssetDeploymentService assetDeploymentService,
            DeploymentMapper deploymentMapper) {
        this.assetDeploymentService = assetDeploymentService;
        this.deploymentMapper = deploymentMapper;
    }

    /**
     * Initiates a new on-chain deployment for the asset.
     */
    @PostMapping
    public ResponseEntity<DeploymentResponse> deployToChain(
            @PathVariable UUID assetId,
            @RequestBody @Valid DeploymentCreateRequest request,
            Authentication auth) {
        UUID actorId = extractActorId(auth);
        AssetDeployment deployment = assetDeploymentService.deploy(
            assetId, request.chain(), request.network(), actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(deploymentMapper.toResponse(deployment));
    }

    /**
     * Lists all deployments for an asset.
     */
    @GetMapping
    public ResponseEntity<List<DeploymentResponse>> listDeployments(@PathVariable UUID assetId) {
        List<AssetDeployment> deployments = assetDeploymentService.listDeployments(assetId);
        return ResponseEntity.ok(deployments.stream().map(deploymentMapper::toResponse).toList());
    }

    /**
     * Returns a single deployment by ID.
     */
    @GetMapping("/{depId}")
    public ResponseEntity<DeploymentResponse> getDeployment(
            @PathVariable UUID assetId,
            @PathVariable UUID depId) {
        AssetDeployment deployment = assetDeploymentService.getDeployment(depId);
        return ResponseEntity.ok(deploymentMapper.toResponse(deployment));
    }

    /**
     * Triggers a chain sync to confirm deployment status.
     */
    @PostMapping("/{depId}/sync")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> syncDeployment(
            @PathVariable UUID assetId,
            @PathVariable UUID depId) {
        assetDeploymentService.syncFromChain(depId);
        return ResponseEntity.accepted().build();
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
}
