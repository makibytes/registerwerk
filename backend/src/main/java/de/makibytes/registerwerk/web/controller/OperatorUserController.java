package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.admin.OperatorUserService;
import de.makibytes.registerwerk.domain.enums.AppUserRole;
import de.makibytes.registerwerk.web.dto.OperatorInviteRequest;
import de.makibytes.registerwerk.web.dto.OperatorUserResponse;
import de.makibytes.registerwerk.web.dto.UpdateCompanyUserRolesRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class OperatorUserController {

    private final OperatorUserService operatorUserService;

    public OperatorUserController(OperatorUserService operatorUserService) {
        this.operatorUserService = operatorUserService;
    }

    @GetMapping
    public ResponseEntity<Page<OperatorUserResponse>> list(
            @RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) AppUserRole role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean operatorOnly,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(
            operatorUserService.list(legalEntityId, role, enabled, operatorOnly, search, page, size)
        );
    }

    @GetMapping("/{userId}")
    public ResponseEntity<OperatorUserResponse> get(@PathVariable UUID userId) {
        return ResponseEntity.ok(operatorUserService.get(userId));
    }

    @PostMapping
    public ResponseEntity<OperatorUserResponse> invite(
            Authentication authentication,
            @Valid @RequestBody OperatorInviteRequest request) {
        return ResponseEntity.ok(operatorUserService.invite(authentication, request));
    }

    @PatchMapping("/{userId}/roles")
    public ResponseEntity<OperatorUserResponse> updateRoles(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateCompanyUserRolesRequest request) {
        return ResponseEntity.ok(operatorUserService.updateRoles(authentication, userId, request));
    }

    @PostMapping("/{userId}/enable")
    public ResponseEntity<OperatorUserResponse> enableUser(
            Authentication authentication,
            @PathVariable UUID userId) {
        return ResponseEntity.ok(operatorUserService.setEnabled(authentication, userId, true));
    }

    @PostMapping("/{userId}/disable")
    public ResponseEntity<OperatorUserResponse> disableUser(
            Authentication authentication,
            @PathVariable UUID userId) {
        return ResponseEntity.ok(operatorUserService.setEnabled(authentication, userId, false));
    }

    @PostMapping("/{userId}/password-reset")
    public ResponseEntity<Void> passwordReset(
            Authentication authentication,
            @PathVariable UUID userId) {
        operatorUserService.sendPasswordReset(authentication, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> delete(
            Authentication authentication,
            @PathVariable UUID userId) {
        operatorUserService.delete(authentication, userId);
        return ResponseEntity.noContent().build();
    }
}
