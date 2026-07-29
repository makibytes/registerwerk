package de.makibytes.registerwerk.corporateactions.web;

import de.makibytes.registerwerk.corporateactions.internal.CorporateActionConfirmationService;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Customer self-service corporate-action confirmation — a document type that was entirely
 * missing before {@code CorporateActionConfirmationService}: an investor had no way to obtain
 * proof that a coupon/dividend paid out to their own holding.
 * GET /api/v1/me/corporate-actions/{corporateActionId}/confirmation
 */
@RestController
public class MeCorporateActionConfirmationController {

    private final CorporateActionConfirmationService confirmationService;

    MeCorporateActionConfirmationController(CorporateActionConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @GetMapping("/api/v1/me/corporate-actions/{corporateActionId}/confirmation")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> myConfirmation(@PathVariable UUID corporateActionId, Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) {
            return ResponseEntity.badRequest().build();
        }
        byte[] pdf = confirmationService.generateForInvestor(corporateActionId, entityId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("confirmation-" + corporateActionId + ".pdf").build().toString())
                .body(pdf);
    }
}
