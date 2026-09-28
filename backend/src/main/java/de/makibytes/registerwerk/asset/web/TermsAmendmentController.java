package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.internal.TermsAmendmentService;
import de.makibytes.registerwerk.asset.web.dto.AssetResponse;
import de.makibytes.registerwerk.asset.web.dto.TermsAmendmentRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * {@code POST /api/v1/assets/{assetId}/terms-amendments} — the only way to change economic terms
 * (ISIN, currency, size, denomination, dates, investment limits, bond terms) once an asset is
 * approved (T3-10). Operator-only, step-up plus a second REGISTRY_ADMIN approver; the audit entry
 * records before/after values, the legal basis and the second approver.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/terms-amendments")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class TermsAmendmentController {

    private final TermsAmendmentService termsAmendmentService;
    private final AssetMapper assetMapper;

    public TermsAmendmentController(TermsAmendmentService termsAmendmentService, AssetMapper assetMapper) {
        this.termsAmendmentService = termsAmendmentService;
        this.assetMapper = assetMapper;
    }

    @PostMapping
    @RequiresStepUp(requireSecondApprover = true, reason = "TERMS_AMENDMENT")
    public ResponseEntity<AssetResponse> amend(
            @PathVariable UUID assetId,
            @Valid @RequestBody TermsAmendmentRequest request,
            Authentication auth,
            HttpServletRequest httpRequest) {
        // Read in the body, not via @RequestAttribute: MVC resolves arguments before the
        // @RequiresStepUp aspect runs and sets this attribute, so a parameter would be null.
        UUID approverId = (UUID) httpRequest.getAttribute(StepUpAttributes.DUAL_CONTROL_APPROVER_ID);
        return ResponseEntity.ok(assetMapper.toResponse(termsAmendmentService.amend(assetId, request,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), approverId)));
    }
}
