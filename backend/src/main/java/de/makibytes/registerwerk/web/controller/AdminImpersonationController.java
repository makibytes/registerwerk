package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.admin.AdminImpersonationService;
import de.makibytes.registerwerk.web.dto.ImpersonateRequest;
import de.makibytes.registerwerk.web.dto.ImpersonateResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/impersonation")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class AdminImpersonationController {

    private final AdminImpersonationService impersonationService;

    public AdminImpersonationController(AdminImpersonationService impersonationService) {
        this.impersonationService = impersonationService;
    }

    @PostMapping
    public ResponseEntity<ImpersonateResponse> impersonate(
            Authentication authentication,
            @Valid @RequestBody ImpersonateRequest request) {
        return ResponseEntity.ok(impersonationService.impersonate(authentication, request.entityId()));
    }
}
