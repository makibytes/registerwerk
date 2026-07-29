package de.makibytes.registerwerk.orgidentity.web;

import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinition;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.internal.PermissionAdminService;
import de.makibytes.registerwerk.orgidentity.web.dto.PermissionDtos.CreatePermissionRequest;
import de.makibytes.registerwerk.orgidentity.web.dto.PermissionDtos.OrgGrantRequest;
import de.makibytes.registerwerk.orgidentity.web.dto.PermissionDtos.PermissionDefinitionResponse;
import de.makibytes.registerwerk.orgidentity.web.dto.PermissionDtos.PermissionGrantResponse;
import de.makibytes.registerwerk.orgidentity.web.dto.PermissionDtos.TrustedIssuerRequest;
import de.makibytes.registerwerk.orgidentity.web.dto.PermissionDtos.TrustedIssuerResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Operator administration of the ecosystem permission framework: definitions,
 * org-level grants and ecosystem-wide trusted claim issuers.
 */
@RestController
@RequestMapping("/api/v1/permissions")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class PermissionAdminController {

    private final PermissionAdminService permissionAdminService;
    private final PermissionDefinitionRepository definitionRepository;
    private final PermissionGrantRepository grantRepository;
    private final OrgRegistrationRepository registrationRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final EcosystemTrustedIssuerRepository trustedIssuerRepository;

    public PermissionAdminController(
            PermissionAdminService permissionAdminService,
            PermissionDefinitionRepository definitionRepository,
            PermissionGrantRepository grantRepository,
            OrgRegistrationRepository registrationRepository,
            LegalEntityRepository legalEntityRepository,
            EcosystemTrustedIssuerRepository trustedIssuerRepository) {
        this.permissionAdminService = permissionAdminService;
        this.definitionRepository = definitionRepository;
        this.grantRepository = grantRepository;
        this.registrationRepository = registrationRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.trustedIssuerRepository = trustedIssuerRepository;
    }

    // ── Definitions ───────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<PermissionDefinitionResponse> createPermission(
            @Valid @RequestBody CreatePermissionRequest request, Authentication auth) {
        PermissionDefinition definition = permissionAdminService.define(
                request.code(), request.name(), request.description(),
                null, PermissionDefinitionStatus.ACTIVE,
                SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PermissionDefinitionResponse.from(definition));
    }

    @GetMapping
    public ResponseEntity<List<PermissionDefinitionResponse>> listPermissions() {
        List<PermissionDefinitionResponse> definitions = definitionRepository
                .findAll(Sort.by(Sort.Direction.ASC, "code")).stream()
                .map(PermissionDefinitionResponse::from)
                .toList();
        return ResponseEntity.ok(definitions);
    }

    // ── Org grants (operator tier) ────────────────────────────────────────────

    @GetMapping("/{definitionId}/grants")
    public ResponseEntity<List<PermissionGrantResponse>> listGrants(@PathVariable UUID definitionId) {
        PermissionDefinition definition = permissionAdminService.requireDefinition(definitionId);
        List<PermissionGrantResponse> grants = grantRepository
                .findByPermissionDefinitionIdOrderByCreatedAtDesc(definitionId).stream()
                .map(grant -> toGrantResponse(grant, definition.getCode()))
                .toList();
        return ResponseEntity.ok(grants);
    }

    @PostMapping("/{definitionId}/org-grants")
    @RequiresStepUp(requireSecondApprover = true, reason = "Permission grant to organization")
    public ResponseEntity<PermissionGrantResponse> grantToOrg(
            @PathVariable UUID definitionId,
            @Valid @RequestBody OrgGrantRequest request,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID dualControlApproverId,
            Authentication auth) {
        PermissionDefinition definition = permissionAdminService.requireDefinition(definitionId);
        PermissionGrant grant = permissionAdminService.grantToOrg(
                definitionId, request.orgRegistrationId(),
                SecurityUtils.extractUserId(auth), primaryRole(auth), dualControlApproverId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toGrantResponse(grant, definition.getCode()));
    }

    @DeleteMapping("/org-grants/{grantId}")
    @RequiresStepUp(requireSecondApprover = true, reason = "Permission grant revocation")
    public ResponseEntity<PermissionGrantResponse> revokeGrant(
            @PathVariable UUID grantId,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID dualControlApproverId,
            Authentication auth) {
        PermissionGrant grant = permissionAdminService.revokeGrant(
                grantId, null, SecurityUtils.extractUserId(auth), primaryRole(auth), dualControlApproverId);
        PermissionDefinition definition = permissionAdminService.requireDefinition(grant.getPermissionDefinitionId());
        return ResponseEntity.ok(toGrantResponse(grant, definition.getCode()));
    }

    // ── Ecosystem trusted issuers ─────────────────────────────────────────────

    @GetMapping("/trusted-issuers")
    public ResponseEntity<List<TrustedIssuerResponse>> listTrustedIssuers() {
        List<TrustedIssuerResponse> issuers = trustedIssuerRepository
                .findAllByOrderByCreatedAtDesc().stream()
                .map(TrustedIssuerResponse::from)
                .toList();
        return ResponseEntity.ok(issuers);
    }

    @PostMapping("/trusted-issuers")
    @RequiresStepUp(requireSecondApprover = true, reason = "Trusted claim issuer registration")
    public ResponseEntity<TrustedIssuerResponse> addTrustedIssuer(
            @Valid @RequestBody TrustedIssuerRequest request,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID dualControlApproverId,
            Authentication auth) {
        var issuer = permissionAdminService.addTrustedIssuer(
                request.chainConfigId(), request.issuerAddress(), request.claimTopics(),
                request.legalEntityId(), SecurityUtils.extractUserId(auth), primaryRole(auth), dualControlApproverId);
        return ResponseEntity.status(HttpStatus.CREATED).body(TrustedIssuerResponse.from(issuer));
    }

    @DeleteMapping("/trusted-issuers/{issuerId}")
    @RequiresStepUp(requireSecondApprover = true, reason = "Trusted claim issuer removal")
    public ResponseEntity<TrustedIssuerResponse> removeTrustedIssuer(
            @PathVariable UUID issuerId,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID dualControlApproverId,
            Authentication auth) {
        var issuer = permissionAdminService.removeTrustedIssuer(
                issuerId, SecurityUtils.extractUserId(auth), primaryRole(auth), dualControlApproverId);
        return ResponseEntity.ok(TrustedIssuerResponse.from(issuer));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PermissionGrantResponse toGrantResponse(PermissionGrant grant, String permissionCode) {
        String entityName = registrationRepository.findById(grant.getOrgRegistrationId())
                .flatMap(org -> legalEntityRepository.findById(org.getLegalEntityId()))
                .map(entity -> entity.getCurrentName())
                .orElse(null);
        return PermissionGrantResponse.from(grant, permissionCode, entityName);
    }

    private static String primaryRole(Authentication auth) {
        return SecurityUtils.extractRoles(auth).stream().findFirst().orElse("REGISTRY_ADMIN");
    }
}
