package de.makibytes.registerwerk.admin.web;

import de.makibytes.registerwerk.admin.internal.AdminImpersonationService;
import de.makibytes.registerwerk.admin.web.dto.ImpersonationSessionView;
import de.makibytes.registerwerk.shared.SecurityUtils;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets an entity's admins see every operator impersonation session on their entity (6-31). */
@RestController
@RequestMapping("/api/v1/company/impersonation-sessions")
@PreAuthorize("hasRole('COMPANY_ADMIN')")
public class CompanyImpersonationController {

    private final AdminImpersonationService service;

    public CompanyImpersonationController(AdminImpersonationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ImpersonationSessionView> list(Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) {
            throw new AccessDeniedException("No entity scope");
        }
        return service.sessionsFor(entityId);
    }
}
