package de.makibytes.registerwerk.admin.web;

import de.makibytes.registerwerk.admin.internal.AdminImpersonationService;
import de.makibytes.registerwerk.admin.web.dto.ImpersonateRequest;
import de.makibytes.registerwerk.admin.web.dto.ImpersonateResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Deliberately NOT under /api/v1/admin/** — that prefix is IP-restricted at the Kong gateway
// (see gateway/kong.yml) for genuinely operator-network-only endpoints. Impersonation, unlike
// those, is legitimately invoked by the CUSTOMER portal (the operator's "view as this customer"
// handoff goes through Kong), so it must live on a path Kong routes without the IP allowlist.
// Authorization is unchanged: still REGISTRY_ADMIN-only, enforced by the JWT role check below.
@RestController
@RequestMapping("/api/v1/impersonation")
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
