package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.internal.GasSponsorshipService;
import de.makibytes.registerwerk.asset.web.dto.GasSponsorshipPolicyCreateRequest;
import de.makibytes.registerwerk.asset.web.dto.GasSponsorshipPolicyResponse;
import de.makibytes.registerwerk.deployment.api.GasSponsorshipPolicy;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Configures who pays gas for a customer's sponsored (ERC-4337) transactions: a per-deployment
 * override, or an issuer-level default applied to future deployments — see
 * {@code EwpgPaymaster.sol} and {@code docs/platform/account-abstraction.md}.
 */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class GasSponsorshipController {

    private final GasSponsorshipService gasSponsorshipService;

    public GasSponsorshipController(GasSponsorshipService gasSponsorshipService) {
        this.gasSponsorshipService = gasSponsorshipService;
    }

    /** Creates (or replaces) the sponsorship policy for one specific asset deployment. */
    @PostMapping("/assets/{assetId}/deployments/{depId}/gas-sponsorship")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') and @deploymentAccessChecker.belongsToAsset(#depId, #assetId)")
    public ResponseEntity<GasSponsorshipPolicyResponse> createForDeployment(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            @RequestBody @Valid GasSponsorshipPolicyCreateRequest request) {
        GasSponsorshipPolicy policy = new GasSponsorshipPolicy();
        policy.setSponsor(request.sponsor());
        policy.setMonthlyCapEth(request.monthlyCapEth());
        GasSponsorshipPolicy created = gasSponsorshipService.createForDeployment(depId, policy);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    /** Deactivates a gas-sponsorship policy (deployment-scoped or issuer-default). */
    @DeleteMapping("/gas-sponsorship/{policyId}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID policyId) {
        gasSponsorshipService.deactivate(policyId);
        return ResponseEntity.noContent().build();
    }

    /** Creates (or replaces) an issuer's default sponsorship policy for future deployments. */
    @PostMapping("/entities/{entityId}/gas-sponsorship-default")
    public ResponseEntity<GasSponsorshipPolicyResponse> createIssuerDefault(
            @PathVariable UUID entityId,
            @RequestBody @Valid GasSponsorshipPolicyCreateRequest request) {
        GasSponsorshipPolicy policy = new GasSponsorshipPolicy();
        policy.setSponsor(request.sponsor());
        policy.setMonthlyCapEth(request.monthlyCapEth());
        GasSponsorshipPolicy created = gasSponsorshipService.createIssuerDefault(entityId, policy);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    /** Lists all sponsorship policies (deployment overrides and the default) for an issuer. */
    @GetMapping("/entities/{entityId}/gas-sponsorship-policies")
    public ResponseEntity<List<GasSponsorshipPolicyResponse>> listForIssuer(@PathVariable UUID entityId) {
        List<GasSponsorshipPolicyResponse> policies =
            gasSponsorshipService.listForIssuer(entityId).stream().map(this::toResponse).toList();
        return ResponseEntity.ok(policies);
    }

    /** Resolves the policy that actually applies to a deployment (override, then issuer default). */
    @GetMapping("/assets/{assetId}/deployments/{depId}/gas-sponsorship")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') and @deploymentAccessChecker.belongsToAsset(#depId, #assetId)")
    public ResponseEntity<GasSponsorshipPolicyResponse> getEffectivePolicy(
            @PathVariable UUID assetId,
            @PathVariable UUID depId) {
        return gasSponsorshipService.resolveEffectivePolicy(depId)
            .map(policy -> ResponseEntity.ok(toResponse(policy)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private GasSponsorshipPolicyResponse toResponse(GasSponsorshipPolicy policy) {
        return new GasSponsorshipPolicyResponse(
            policy.getId(),
            policy.getAssetDeploymentId(),
            policy.getIssuerId(),
            policy.getSponsor(),
            policy.getMonthlyCapEth(),
            policy.getActive(),
            policy.getCreatedAt()
        );
    }
}
