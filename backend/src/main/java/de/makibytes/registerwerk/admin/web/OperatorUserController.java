package de.makibytes.registerwerk.admin.web;

import de.makibytes.registerwerk.admin.internal.OperatorUserService;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.admin.web.dto.DisableUserRequest;
import de.makibytes.registerwerk.admin.web.dto.EnableUserRequest;
import de.makibytes.registerwerk.admin.web.dto.OperatorInviteRequest;
import de.makibytes.registerwerk.admin.web.dto.RebindIdentityRequest;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.admin.web.dto.OperatorUserResponse;
import de.makibytes.registerwerk.customer.web.dto.UpdateCompanyUserRolesRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
@Validated
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
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        return ResponseEntity.ok(
            operatorUserService.list(legalEntityId, role, enabled, operatorOnly, search, page, size)
        );
    }

    @GetMapping("/{userId}")
    public ResponseEntity<OperatorUserResponse> get(@PathVariable UUID userId) {
        return ResponseEntity.ok(operatorUserService.get(userId));
    }

    @PostMapping
    @RequiresStepUp(reason = "OPERATOR_USER_ADMIN")
    public ResponseEntity<OperatorUserResponse> invite(
            Authentication authentication,
            @Valid @RequestBody OperatorInviteRequest request) {
        return ResponseEntity.ok(operatorUserService.invite(authentication, request));
    }

    @PatchMapping("/{userId}/roles")
    @RequiresStepUp(reason = "OPERATOR_USER_ADMIN")
    public ResponseEntity<OperatorUserResponse> updateRoles(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateCompanyUserRolesRequest request) {
        return ResponseEntity.ok(operatorUserService.updateRoles(authentication, userId, request));
    }

    @PostMapping("/{userId}/enable")
    @RequiresStepUp(reason = "OPERATOR_USER_ADMIN")
    public ResponseEntity<OperatorUserResponse> enableUser(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody(required = false) EnableUserRequest request) {
        return ResponseEntity.ok(operatorUserService.setEnabled(authentication, userId, true,
            request == null ? null : request.reinstatementReason()));
    }

    @PostMapping("/{userId}/disable")
    @RequiresStepUp(reason = "OPERATOR_USER_ADMIN")
    public ResponseEntity<OperatorUserResponse> disableUser(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody(required = false) DisableUserRequest request) {
        return ResponseEntity.ok(operatorUserService.setEnabled(authentication, userId, false,
            request == null ? null : request.reason()));
    }

    /** Sanctioned re-bind of an IdP identity (step-up + second approver, mandatory reason). */
    @PostMapping("/{userId}/reset-identity")
    @RequiresStepUp(reason = "IDENTITY_REBIND", requireSecondApprover = true)
    public ResponseEntity<OperatorUserResponse> resetIdentity(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody RebindIdentityRequest request) {
        return ResponseEntity.ok(operatorUserService.resetIdentityBinding(authentication, userId, request.reason()));
    }

    @PostMapping("/{userId}/password-reset")
    public ResponseEntity<Void> passwordReset(
            Authentication authentication,
            @PathVariable UUID userId) {
        operatorUserService.sendPasswordReset(authentication, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}")
    @RequiresStepUp(reason = "OPERATOR_USER_ADMIN")
    public ResponseEntity<Void> delete(
            Authentication authentication,
            @PathVariable UUID userId) {
        operatorUserService.delete(authentication, userId);
        return ResponseEntity.noContent().build();
    }
}
