package de.makibytes.registerwerk.corporateactions.web;

import de.makibytes.registerwerk.shared.RegisterClock;
import de.makibytes.registerwerk.corporateactions.internal.PositionStatementService;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Position statement (Depotauszug) endpoints.
 * Customer: GET /api/v1/me/statements — own portfolio
 * Operator: GET /api/v1/customers/{entityId}/statements — any entity
 */
@RestController
public class PositionStatementController {

    private final PositionStatementService service;
    private final RegisterClock registerClock;

    PositionStatementController(PositionStatementService service, RegisterClock registerClock) {
        this.registerClock = registerClock;
        this.service = service;
    }

    @GetMapping("/api/v1/me/statements")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> myStatement(Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) {
            return ResponseEntity.badRequest().build();
        }
        return buildPdfResponse(entityId);
    }

    @GetMapping("/api/v1/customers/{entityId}/statements")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT')")
    public ResponseEntity<byte[]> entityStatement(@PathVariable UUID entityId) {
        return buildPdfResponse(entityId);
    }

    private ResponseEntity<byte[]> buildPdfResponse(UUID entityId) {
        byte[] pdf = service.generateForEntity(entityId);
        String filename = "depotauszug-" + entityId + "-" + registerClock.today() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(pdf);
    }
}
