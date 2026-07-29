package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrant;
import de.makibytes.registerwerk.asset.internal.AssetTokenAdminGrantService;
import de.makibytes.registerwerk.asset.web.dto.AssetTokenAdminGrantRequest;
import de.makibytes.registerwerk.asset.web.dto.GrantRevokeRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Asset-scoped ASSET_TOKEN_ADMIN grant management. Deliberately named "token-admin-grants",
 * not "permissions" — kept distinct from the unrelated orgidentity ecosystem-permission API
 * at {@code /api/v1/permissions} (dApp-marketplace-oriented, org-flat, no asset dimension).
 * All state-mutating operations require REGISTRY_ADMIN + step-up + 4-eyes.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/token-admin-grants")
@PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('COMPLIANCE_OFFICER')")
public class AssetTokenAdminGrantController {

    private final AssetTokenAdminGrantService service;

    AssetTokenAdminGrantController(AssetTokenAdminGrantService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<AssetTokenAdminGrant>> list(@PathVariable UUID assetId) {
        return ResponseEntity.ok(service.findByAsset(assetId));
    }

    @PostMapping
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "ASSET_TOKEN_ADMIN_GRANT")
    public ResponseEntity<AssetTokenAdminGrant> create(
            @PathVariable UUID assetId,
            @RequestBody @Valid AssetTokenAdminGrantRequest req,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID dualControlApproverId,
            Authentication auth) {
        if (req.entityId() == null) {
            throw new IllegalArgumentException("entityId is required to grant ASSET_TOKEN_ADMIN on an asset");
        }
        AssetTokenAdminGrant grant = req.toEntity(assetId);
        UUID createdBy = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.grant(grant, createdBy, actorRole, dualControlApproverId));
    }

    @PostMapping("/{grantId}/revoke")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "ASSET_TOKEN_ADMIN_REVOKE")
    public ResponseEntity<AssetTokenAdminGrant> revoke(
            @PathVariable UUID assetId,
            @PathVariable UUID grantId,
            @RequestBody @Valid GrantRevokeRequest req,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID dualControlApproverId,
            Authentication auth) {
        UUID revokedBy = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN");
        return ResponseEntity.ok(
                service.revoke(grantId, revokedBy, actorRole, req.reason(), dualControlApproverId));
    }
}
