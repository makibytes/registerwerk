package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.customer.CompanyUserService;
import de.makibytes.registerwerk.web.dto.CompanyIdpSettingsRequest;
import de.makibytes.registerwerk.web.dto.CompanyIdpSettingsResponse;
import de.makibytes.registerwerk.web.dto.CompanyMeResponse;
import de.makibytes.registerwerk.web.dto.CompanyUserResponse;
import de.makibytes.registerwerk.web.dto.InviteCompanyUserRequest;
import de.makibytes.registerwerk.web.dto.UpdateCompanyUserRolesRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/company")
@PreAuthorize("hasRole('COMPANY_ADMIN')")
public class CompanyController {

    private final CompanyUserService companyUserService;

    public CompanyController(CompanyUserService companyUserService) {
        this.companyUserService = companyUserService;
    }

    @GetMapping("/me")
    public ResponseEntity<CompanyMeResponse> getMyEntity(Authentication authentication) {
        companyUserService.syncAuthenticatedPrincipal(authentication);
        return ResponseEntity.ok(companyUserService.getMyEntity(authentication));
    }

    @GetMapping("/users")
    public ResponseEntity<List<CompanyUserResponse>> listUsers(Authentication authentication) {
        companyUserService.syncAuthenticatedPrincipal(authentication);
        return ResponseEntity.ok(companyUserService.listUsers(authentication));
    }

    @PostMapping("/users/invite")
    public ResponseEntity<CompanyUserResponse> inviteUser(
            Authentication authentication,
            @Valid @RequestBody InviteCompanyUserRequest request) {
        companyUserService.syncAuthenticatedPrincipal(authentication);
        return ResponseEntity.ok(companyUserService.inviteUser(authentication, request));
    }

    @PatchMapping("/users/{userId}/roles")
    public ResponseEntity<CompanyUserResponse> updateRoles(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateCompanyUserRolesRequest request) {
        companyUserService.syncAuthenticatedPrincipal(authentication);
        return ResponseEntity.ok(companyUserService.updateUserRoles(authentication, userId, request));
    }

    @PostMapping("/users/{userId}/disable")
    public ResponseEntity<CompanyUserResponse> disableUser(Authentication authentication, @PathVariable UUID userId) {
        companyUserService.syncAuthenticatedPrincipal(authentication);
        return ResponseEntity.ok(companyUserService.setUserEnabled(authentication, userId, false));
    }

    @PostMapping("/users/{userId}/enable")
    public ResponseEntity<CompanyUserResponse> enableUser(Authentication authentication, @PathVariable UUID userId) {
        companyUserService.syncAuthenticatedPrincipal(authentication);
        return ResponseEntity.ok(companyUserService.setUserEnabled(authentication, userId, true));
    }

    @PostMapping("/users/{userId}/password-reset")
    public ResponseEntity<Void> requestPasswordReset(Authentication authentication, @PathVariable UUID userId) {
        companyUserService.syncAuthenticatedPrincipal(authentication);
        companyUserService.sendPasswordReset(authentication, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Void> deleteUser(Authentication authentication, @PathVariable UUID userId) {
        companyUserService.syncAuthenticatedPrincipal(authentication);
        companyUserService.deleteUser(authentication, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/idp")
    public ResponseEntity<CompanyIdpSettingsResponse> getIdpSettings(Authentication authentication) {
        companyUserService.syncAuthenticatedPrincipal(authentication);
        return ResponseEntity.ok(companyUserService.getIdpSettings(authentication));
    }

    @PutMapping("/idp")
    public ResponseEntity<CompanyIdpSettingsResponse> saveIdpSettings(
            Authentication authentication,
            @RequestBody CompanyIdpSettingsRequest request) {
        companyUserService.syncAuthenticatedPrincipal(authentication);
        return ResponseEntity.ok(companyUserService.saveIdpSettings(authentication, request));
    }
}
