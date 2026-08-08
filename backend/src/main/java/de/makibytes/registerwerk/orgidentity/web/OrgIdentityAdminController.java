package de.makibytes.registerwerk.orgidentity.web;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.internal.MemberWalletService;
import de.makibytes.registerwerk.orgidentity.internal.OrgRegistrationService;
import de.makibytes.registerwerk.orgidentity.web.dto.OrgIdentityDtos.MemberWalletResponse;
import de.makibytes.registerwerk.orgidentity.web.dto.OrgIdentityDtos.OrgRegistrationResponse;
import de.makibytes.registerwerk.orgidentity.web.dto.OrgIdentityDtos.RegisterOrgRequest;
import de.makibytes.registerwerk.orgidentity.web.dto.OrgIdentityDtos.SuspendOrgRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.shared.api.PageResponse;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Operator administration of onchain organizations and their member wallets.
 * Suspension is a regulator-grade action guarded by step-up + 4-eyes.
 */
@RestController
@RequestMapping("/api/v1/org-identity")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
@Validated
public class OrgIdentityAdminController {

    private final OrgRegistrationService registrationService;
    private final MemberWalletService memberWalletService;
    private final OrgRegistrationRepository registrationRepository;
    private final OrgMemberWalletRepository walletRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final ChainConfigRepository chainConfigRepository;

    public OrgIdentityAdminController(
            OrgRegistrationService registrationService,
            MemberWalletService memberWalletService,
            OrgRegistrationRepository registrationRepository,
            OrgMemberWalletRepository walletRepository,
            LegalEntityRepository legalEntityRepository,
            ChainConfigRepository chainConfigRepository) {
        this.registrationService = registrationService;
        this.memberWalletService = memberWalletService;
        this.registrationRepository = registrationRepository;
        this.walletRepository = walletRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.chainConfigRepository = chainConfigRepository;
    }

    @PostMapping("/orgs")
    @RequiresStepUp(reason = "Org registration")
    public ResponseEntity<OrgRegistrationResponse> registerOrg(
            @Valid @RequestBody RegisterOrgRequest request, Authentication auth) {
        OrgRegistration registration = registrationService.register(
                request.legalEntityId(), request.chainConfigId(), request.countryCode(),
                SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(registration));
    }

    @GetMapping("/orgs")
    public ResponseEntity<PageResponse<OrgRegistrationResponse>> listOrgs(
            @RequestParam(required = false) OrgRegistrationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = status != null
                ? registrationRepository.findByStatus(status, pageable)
                : registrationRepository.findAll(pageable);
        return ResponseEntity.ok(PageResponse.of(result.map(this::toResponse)));
    }

    @GetMapping("/orgs/{id}")
    public ResponseEntity<OrgRegistrationResponse> getOrg(@PathVariable UUID id) {
        return ResponseEntity.ok(toResponse(registrationService.require(id)));
    }

    @PostMapping("/orgs/{id}/suspend")
    @RequiresStepUp(requireSecondApprover = true, reason = "Org suspension")
    public ResponseEntity<OrgRegistrationResponse> suspendOrg(
            @PathVariable UUID id, @Valid @RequestBody SuspendOrgRequest request, Authentication auth) {
        OrgRegistration registration = registrationService.suspend(
                id, request.reason(), SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.ok(toResponse(registration));
    }

    @PostMapping("/orgs/{id}/reinstate")
    @RequiresStepUp(requireSecondApprover = true, reason = "Org reinstatement")
    public ResponseEntity<OrgRegistrationResponse> reinstateOrg(@PathVariable UUID id, Authentication auth) {
        OrgRegistration registration = registrationService.reinstate(
                id, SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.ok(toResponse(registration));
    }

    @GetMapping("/orgs/{id}/wallets")
    public ResponseEntity<List<MemberWalletResponse>> listWallets(@PathVariable UUID id) {
        registrationService.require(id);
        List<MemberWalletResponse> wallets = walletRepository
                .findByOrgRegistrationIdOrderByCreatedAtDesc(id).stream()
                .map(MemberWalletResponse::from)
                .toList();
        return ResponseEntity.ok(wallets);
    }

    @DeleteMapping("/orgs/{id}/wallets/{walletId}")
    public ResponseEntity<MemberWalletResponse> removeWallet(
            @PathVariable UUID id, @PathVariable UUID walletId, Authentication auth) {
        registrationService.require(id);
        var wallet = memberWalletService.removeWalletFromOrg(
                id, walletId, SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.ok(MemberWalletResponse.from(wallet));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OrgRegistrationResponse toResponse(OrgRegistration registration) {
        String entityName = legalEntityRepository.findById(registration.getLegalEntityId())
                .map(entity -> entity.getCurrentName())
                .orElse(null);
        String chainIdentifier = chainConfigRepository.findById(registration.getChainConfigId())
                .map(chain -> chain.getIdentifier())
                .orElse(null);
        long activeMembers = walletRepository.countByOrgRegistrationIdAndStatus(
                registration.getId(), MemberWalletStatus.ACTIVE);
        return OrgRegistrationResponse.from(registration, entityName, chainIdentifier, activeMembers);
    }

    private static String primaryRole(Authentication auth) {
        return SecurityUtils.extractRoles(auth).stream().findFirst().orElse("REGISTRY_ADMIN");
    }
}
