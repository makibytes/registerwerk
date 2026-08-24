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
 * Entity-wide ASSET_TOKEN_ADMIN grant management — a grant here has {@code assetId = null}
 * and applies across every asset where the entity is issuer or holder, present and future.
 * See {@code AssetTokenAdminGrantController} for the (more common) asset-scoped variant.
 * All state-mutating operations require REGISTRY_ADMIN + step-up + 4-eyes.
 */
@RestController
@RequestMapping("/api/v1/entities/{entityId}/token-admin-grants")
@PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('COMPLIANCE_OFFICER')")
public class EntityTokenAdminGrantController {

    private final AssetTokenAdminGrantService service;

    EntityTokenAdminGrantController(AssetTokenAdminGrantService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<AssetTokenAdminGrant>> list(@PathVariable UUID entityId) {
        return ResponseEntity.ok(service.findEntityWideByEntity(entityId));
    }

    @PostMapping
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "ASSET_TOKEN_ADMIN_GRANT_ENTITY_WIDE")
    public ResponseEntity<AssetTokenAdminGrant> create(
            @PathVariable UUID entityId,
            @RequestBody @Valid AssetTokenAdminGrantRequest req,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId,
            Authentication auth) {
        AssetTokenAdminGrant grant = req.toEntity(null); // assetId = null => entity-wide
        grant.setEntityId(entityId);
        UUID createdBy = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.grant(grant, createdBy, actorRole, approverId));
    }

    @PostMapping("/{grantId}/revoke")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "ASSET_TOKEN_ADMIN_REVOKE")
    public ResponseEntity<AssetTokenAdminGrant> revoke(
            @PathVariable UUID entityId,
            @PathVariable UUID grantId,
            @RequestBody @Valid GrantRevokeRequest req,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId,
            Authentication auth) {
        UUID revokedBy = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN");
        return ResponseEntity.ok(
                service.revokeEntityWide(entityId, grantId, revokedBy, actorRole, req.reason(), approverId));
    }

}
