package de.makibytes.registerwerk.orgidentity.web;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.WalletBindChallenge;
import de.makibytes.registerwerk.orgidentity.internal.MemberWalletService;
import de.makibytes.registerwerk.orgidentity.internal.PermissionAdminService;
import de.makibytes.registerwerk.orgidentity.web.dto.OrgIdentityDtos.BindWalletRequest;
import de.makibytes.registerwerk.orgidentity.web.dto.OrgIdentityDtos.MemberWalletResponse;
import de.makibytes.registerwerk.orgidentity.web.dto.OrgIdentityDtos.OrgRegistrationResponse;
import de.makibytes.registerwerk.orgidentity.web.dto.OrgIdentityDtos.WalletChallengeRequest;
import de.makibytes.registerwerk.orgidentity.web.dto.OrgIdentityDtos.WalletChallengeResponse;
import de.makibytes.registerwerk.orgidentity.web.dto.PermissionDtos.PermissionGrantResponse;
import de.makibytes.registerwerk.orgidentity.web.dto.PermissionDtos.RoleGrantRequest;
import de.makibytes.registerwerk.orgidentity.web.dto.PermissionDtos.RoleRestrictionRequest;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Company-admin self-service for the entity's onchain organization: viewing the org,
 * binding member wallets (challenge + personal_sign proof), and unbinding them.
 * The entity is always taken from the JWT {@code entity_id} claim — never from the request.
 */
@RestController
@RequestMapping("/api/v1/company/org-identity")
@PreAuthorize("hasAnyRole('COMPANY_ADMIN','REGISTRY_ADMIN')")
public class CompanyOrgIdentityController {

    private final MemberWalletService memberWalletService;
    private final PermissionAdminService permissionAdminService;
    private final OrgRegistrationRepository registrationRepository;
    private final OrgMemberWalletRepository walletRepository;
    private final ChainConfigRepository chainConfigRepository;
    private final PermissionGrantRepository grantRepository;
    private final PermissionDefinitionRepository definitionRepository;

    public CompanyOrgIdentityController(
            MemberWalletService memberWalletService,
            PermissionAdminService permissionAdminService,
            OrgRegistrationRepository registrationRepository,
            OrgMemberWalletRepository walletRepository,
            ChainConfigRepository chainConfigRepository,
            PermissionGrantRepository grantRepository,
            PermissionDefinitionRepository definitionRepository) {
        this.memberWalletService = memberWalletService;
        this.permissionAdminService = permissionAdminService;
        this.registrationRepository = registrationRepository;
        this.walletRepository = walletRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.grantRepository = grantRepository;
        this.definitionRepository = definitionRepository;
    }

    /** All org registrations of the caller's entity (one per chain). */
    @GetMapping
    public ResponseEntity<List<OrgRegistrationResponse>> myOrgs(Authentication auth) {
        UUID entityId = requireEntityId(auth);
        List<OrgRegistrationResponse> orgs = registrationRepository.findByLegalEntityId(entityId).stream()
                .map(reg -> OrgRegistrationResponse.from(reg, null,
                        chainConfigRepository.findById(reg.getChainConfigId())
                                .map(chain -> chain.getIdentifier()).orElse(null),
                        -1))
                .toList();
        return ResponseEntity.ok(orgs);
    }

    @GetMapping("/wallets")
    public ResponseEntity<List<MemberWalletResponse>> myWallets(
            @RequestParam UUID chainConfigId, Authentication auth) {
        OrgRegistration registration = requireOwnRegistration(auth, chainConfigId);
        List<MemberWalletResponse> wallets = walletRepository
                .findByOrgRegistrationIdOrderByCreatedAtDesc(registration.getId()).stream()
                .map(MemberWalletResponse::from)
                .toList();
        return ResponseEntity.ok(wallets);
    }

    @PostMapping("/wallets/challenge")
    public ResponseEntity<WalletChallengeResponse> createChallenge(
            @Valid @RequestBody WalletChallengeRequest request, Authentication auth) {
        UUID entityId = requireEntityId(auth);
        WalletBindChallenge challenge = memberWalletService.createChallenge(
                entityId, request.chainConfigId(), request.walletAddress());
        String message = MemberWalletService.challengeMessage(
                entityId, request.chainConfigId(), request.walletAddress(), challenge.getNonce());
        return ResponseEntity.ok(WalletChallengeResponse.from(challenge, message));
    }

    @PostMapping("/wallets")
    public ResponseEntity<MemberWalletResponse> bindWallet(
            @Valid @RequestBody BindWalletRequest request, Authentication auth) {
        UUID entityId = requireEntityId(auth);
        var wallet = memberWalletService.bindWallet(
                entityId, request.chainConfigId(), request.walletAddress(),
                request.signature(), request.roles(), request.label(),
                SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberWalletResponse.from(wallet));
    }

    @DeleteMapping("/wallets/{walletId}")
    public ResponseEntity<MemberWalletResponse> removeWallet(@PathVariable UUID walletId, Authentication auth) {
        UUID entityId = requireEntityId(auth);
        var wallet = memberWalletService.removeWallet(
                walletId, entityId, SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.ok(MemberWalletResponse.from(wallet));
    }

    // ── Permission grants & role delegation ──────────────────────────────────

    /** All grants of the caller's org on a chain: org-level grants and role delegations. */
    @GetMapping("/grants")
    public ResponseEntity<List<PermissionGrantResponse>> myGrants(
            @RequestParam UUID chainConfigId, Authentication auth) {
        OrgRegistration registration = requireOwnRegistration(auth, chainConfigId);
        List<PermissionGrantResponse> grants = grantRepository
                .findByOrgRegistrationIdOrderByCreatedAtDesc(registration.getId()).stream()
                .map(grant -> PermissionGrantResponse.from(grant,
                        definitionRepository.findById(grant.getPermissionDefinitionId())
                                .map(def -> def.getCode()).orElse(null),
                        null))
                .toList();
        return ResponseEntity.ok(grants);
    }

    /** Delegates an org-granted permission to an org-scoped member role. */
    @PostMapping("/role-grants")
    public ResponseEntity<PermissionGrantResponse> grantToRole(
            @Valid @RequestBody RoleGrantRequest request, Authentication auth) {
        UUID entityId = requireEntityId(auth);
        var grant = permissionAdminService.grantToRole(
                entityId, request.chainConfigId(), request.roleCode(), request.permissionDefinitionId(),
                SecurityUtils.extractUserId(auth), primaryRole(auth));
        String code = definitionRepository.findById(grant.getPermissionDefinitionId())
                .map(def -> def.getCode()).orElse(null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PermissionGrantResponse.from(grant, code, null));
    }

    @DeleteMapping("/role-grants/{grantId}")
    public ResponseEntity<PermissionGrantResponse> revokeRoleGrant(
            @PathVariable UUID grantId, Authentication auth) {
        UUID entityId = requireEntityId(auth);
        var grant = permissionAdminService.revokeRoleGrant(
                grantId, entityId, SecurityUtils.extractUserId(auth), primaryRole(auth));
        String code = definitionRepository.findById(grant.getPermissionDefinitionId())
                .map(def -> def.getCode()).orElse(null);
        return ResponseEntity.ok(PermissionGrantResponse.from(grant, code, null));
    }

    /** Marks an org-granted permission as role-restricted (or lifts the restriction). */
    @PostMapping("/role-restriction")
    public ResponseEntity<PermissionGrantResponse> setRoleRestriction(
            @Valid @RequestBody RoleRestrictionRequest request, Authentication auth) {
        UUID entityId = requireEntityId(auth);
        var grant = permissionAdminService.setRoleRestricted(
                entityId, request.chainConfigId(), request.permissionDefinitionId(), request.restricted(),
                SecurityUtils.extractUserId(auth), primaryRole(auth));
        String code = definitionRepository.findById(grant.getPermissionDefinitionId())
                .map(def -> def.getCode()).orElse(null);
        return ResponseEntity.ok(PermissionGrantResponse.from(grant, code, null));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static UUID requireEntityId(Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) {
            throw new AccessDeniedException("No entity_id claim in token");
        }
        return entityId;
    }

    private OrgRegistration requireOwnRegistration(Authentication auth, UUID chainConfigId) {
        UUID entityId = requireEntityId(auth);
        return registrationRepository.findByLegalEntityIdAndChainConfigId(entityId, chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("OrgRegistration", entityId));
    }

    private static String primaryRole(Authentication auth) {
        return SecurityUtils.extractRoles(auth).stream().findFirst().orElse("COMPANY_ADMIN");
    }
}
