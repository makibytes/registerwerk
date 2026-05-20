package de.makibytes.registerwerk.corporateactions.web;

import de.makibytes.registerwerk.corporateactions.internal.SteuerbescheinigungService;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Steuerbescheinigung endpoints.
 * Customer: GET /api/v1/me/tax-certificates/{year}
 * Operator: GET /api/v1/customers/{entityId}/tax-certificates/{year}
 */
@RestController
public class SteuerbescheinigungController {

    private final SteuerbescheinigungService service;

    SteuerbescheinigungController(SteuerbescheinigungService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/me/tax-certificates/{year}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> myTaxCertificate(@PathVariable int year, Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) return ResponseEntity.badRequest().build();
        return buildPdfResponse(entityId, year);
    }

    @GetMapping("/api/v1/customers/{entityId}/tax-certificates/{year}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT')")
    public ResponseEntity<byte[]> entityTaxCertificate(@PathVariable UUID entityId,
                                                        @PathVariable int year) {
        return buildPdfResponse(entityId, year);
    }

    private ResponseEntity<byte[]> buildPdfResponse(UUID entityId, int year) {
        int currentYear = LocalDate.now().getYear();
        if (year < 2020 || year > currentYear) {
            return ResponseEntity.badRequest().build();
        }
        byte[] pdf = service.generate(entityId, year);
        String filename = "steuerbescheinigung-" + entityId + "-" + year + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(pdf);
    }
}
