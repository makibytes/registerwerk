package de.makibytes.registerwerk.corporateactions.web;

import de.makibytes.registerwerk.corporateactions.internal.CorporateActionConfirmationService;
import de.makibytes.registerwerk.corporateactions.internal.CorporateActionService;
import de.makibytes.registerwerk.corporateactions.web.dto.CorporateActionView;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Customer self-service corporate-action endpoints — a holder had no way to see corporate
 * actions affecting their own position at all before {@code /corporate-actions?assetId=} below:
 * the confirmation-download endpoints ({@code /confirmation}, {@code /confirmation/iso20022})
 * already existed but had zero frontend caller on either app, since nothing listed a holder's
 * corporate actions to get an id from in the first place.
 *
 * <p>Renamed from {@code MeCorporateActionConfirmationController} now that it does more than
 * confirmations.
 */
@RestController
public class MeCorporateActionController {

    private final CorporateActionService corporateActionService;
    private final CorporateActionConfirmationService confirmationService;

    MeCorporateActionController(CorporateActionService corporateActionService,
                                 CorporateActionConfirmationService confirmationService) {
        this.corporateActionService = corporateActionService;
        this.confirmationService = confirmationService;
    }

    /**
     * Corporate actions for one asset, scoped to holdings the caller actually has — excludes
     * {@code PROPOSED}/{@code REJECTED}: an investor sees register facts, not an issuer's drafts.
     * Filtering happens in {@link CorporateActionService#findByAssetForHolder} via the dedicated
     * {@code findByAssetIdAndStatusIn} query, not a Java-side filter here. Authorization is
     * expressed entirely in the SpEL below (a runtime bean-name lookup, not a Java import of
     * {@code asset.web.AssetAccessChecker} — {@code web} isn't a cross-module surface, only
     * {@code api} is) exactly like {@code IssuerCorporateActionController}'s class-level annotation.
     */
    @GetMapping("/api/v1/me/corporate-actions")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT') "
            + "or @assetAccessChecker.isHolderOfAsset(#assetId, authentication) "
            + "or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<List<CorporateActionView>> myCorporateActions(@RequestParam UUID assetId) {
        List<CorporateActionView> views = corporateActionService.findByAssetForHolder(assetId).stream()
                .map(CorporateActionView::of)
                .toList();
        return ResponseEntity.ok(views);
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

    @GetMapping("/api/v1/me/corporate-actions/{corporateActionId}/confirmation/iso20022")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> myIso20022Confirmation(@PathVariable UUID corporateActionId, Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) {
            return ResponseEntity.badRequest().build();
        }
        byte[] xml = confirmationService.generateIso20022ForInvestor(corporateActionId, entityId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("confirmation-" + corporateActionId + ".xml").build().toString())
                .body(xml);
    }
}
