package de.makibytes.registerwerk.finality.web;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.finality.internal.FinalityPolicyAdminService;
import de.makibytes.registerwerk.finality.internal.FinalityPolicyAssignmentView;
import de.makibytes.registerwerk.finality.internal.FinalityPolicyOverrideView;
import de.makibytes.registerwerk.finality.web.dto.CreateOverrideRequest;
import de.makibytes.registerwerk.finality.web.dto.SetProfileRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for the finality policy model. No gate call sites consult this yet ({@code
 * FinalityGate} is a later phase) — this only changes what {@code FinalityPolicyService} would
 * report if asked.
 *
 * <p>Reads are open to every role that needs visibility into what will gate an asset's actions
 * (REGISTRY_ADMIN, AUDIT, COMPLIANCE_OFFICER, ISSUER — the last so an issuer can see why an
 * operation on their own asset isn't available yet, once the gate exists). Writes are
 * REGISTRY_ADMIN-only and step-up protected, same as {@code ChainConfigController}, which this
 * screen sits next to — {@code chain_config.finality_model} and the policy that gates on top of
 * it belong in the same place.
 */
@RestController
@RequestMapping("/api/v1/finality-policies")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN','AUDIT','COMPLIANCE_OFFICER','ISSUER')")
public class FinalityPolicyController {

    private final FinalityPolicyAdminService adminService;

    public FinalityPolicyController(FinalityPolicyAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/assignments")
    public ResponseEntity<List<FinalityPolicyAssignmentView>> listAssignments() {
        return ResponseEntity.ok(adminService.listAssignments());
    }

    @GetMapping("/assets/{assetId}/overrides")
    public ResponseEntity<List<FinalityPolicyOverrideView>> listOverrides(@PathVariable UUID assetId) {
        return ResponseEntity.ok(adminService.listOverridesForAsset(assetId));
    }

    /** Also serves as documentation of the valid {@code operation} values for
     *  {@link #createOverride}. */
    @GetMapping("/operations")
    public ResponseEntity<List<String>> listOperations() {
        return ResponseEntity.ok(java.util.Arrays.stream(GatedOperation.values()).map(Enum::name).toList());
    }

    @PutMapping("/global")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "FINALITY_POLICY_GLOBAL_PROFILE_SET")
    public ResponseEntity<FinalityPolicyAssignmentView> setGlobalProfile(
            @Valid @RequestBody SetProfileRequest request, Authentication auth) {
        return ResponseEntity.ok(adminService.setGlobalProfile(
                request.profile(), SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PutMapping("/token-standards/{tokenStandard}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "FINALITY_POLICY_TOKEN_STANDARD_PROFILE_SET")
    public ResponseEntity<FinalityPolicyAssignmentView> setTokenStandardProfile(
            @PathVariable TokenStandard tokenStandard,
            @Valid @RequestBody SetProfileRequest request, Authentication auth) {
        return ResponseEntity.ok(adminService.setTokenStandardProfile(tokenStandard, request.profile(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PutMapping("/assets/{assetId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "FINALITY_POLICY_ASSET_PROFILE_SET")
    public ResponseEntity<FinalityPolicyAssignmentView> setAssetProfile(
            @PathVariable UUID assetId,
            @Valid @RequestBody SetProfileRequest request, Authentication auth) {
        return ResponseEntity.ok(adminService.setAssetProfile(assetId, request.profile(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @DeleteMapping("/assignments/{assignmentId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "FINALITY_POLICY_ASSIGNMENT_DELETED")
    public ResponseEntity<Void> deleteAssignment(@PathVariable UUID assignmentId, Authentication auth) {
        adminService.deleteAssignment(assignmentId,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assets/{assetId}/overrides")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "FINALITY_POLICY_OVERRIDE_SET")
    public ResponseEntity<FinalityPolicyOverrideView> createOverride(
            @PathVariable UUID assetId, @Valid @RequestBody CreateOverrideRequest request, Authentication auth) {
        // Validates the operation name up front — GatedOperation.valueOf throws
        // IllegalArgumentException on a bad value, which GlobalExceptionHandler maps to 400.
        GatedOperation.valueOf(request.operation());
        return ResponseEntity.ok(adminService.createOverride(assetId, request.operation(), request.requiredLevel(),
                request.reason(), SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @DeleteMapping("/overrides/{overrideId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "FINALITY_POLICY_OVERRIDE_DELETED")
    public ResponseEntity<Void> deleteOverride(@PathVariable UUID overrideId, Authentication auth) {
        adminService.deleteOverride(overrideId,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.noContent().build();
    }
}
