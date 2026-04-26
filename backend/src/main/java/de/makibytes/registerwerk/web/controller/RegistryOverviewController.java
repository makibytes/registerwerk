package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.customer.RegistryOverviewService;
import de.makibytes.registerwerk.web.dto.registry.RegistryOverviewResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/entities")
public class RegistryOverviewController {

    private final RegistryOverviewService registryOverviewService;

    public RegistryOverviewController(RegistryOverviewService registryOverviewService) {
        this.registryOverviewService = registryOverviewService;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT')")
    public ResponseEntity<RegistryOverviewResponse> getOverview() {
        return ResponseEntity.ok(registryOverviewService.getOverview());
    }
}
