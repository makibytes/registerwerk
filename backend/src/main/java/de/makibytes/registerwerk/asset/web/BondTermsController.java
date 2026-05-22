package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.asset.web.dto.BondTermsRequest;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Bond terms management for assets with bond-type token standards.
 *
 * <pre>
 *   POST /api/v1/assets/{assetId}/bond-terms — create or update bond terms
 *   GET  /api/v1/assets/{assetId}/bond-terms — retrieve bond terms
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/bond-terms")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class BondTermsController {

    private final AssetBondTermsRepository bondTermsRepository;

    public BondTermsController(AssetBondTermsRepository bondTermsRepository) {
        this.bondTermsRepository = bondTermsRepository;
    }

    @PostMapping
    public ResponseEntity<AssetBondTerms> upsertBondTerms(
            @PathVariable UUID assetId,
            @Valid @RequestBody BondTermsRequest request) {
        AssetBondTerms terms = bondTermsRepository.findById(assetId)
                .orElseGet(() -> { AssetBondTerms t = new AssetBondTerms(); t.setAssetId(assetId); return t; });

        terms.setFaceValue(request.faceValue());
        terms.setCurrencyIso(request.currencyIso());
        terms.setIssueDate(request.issueDate());
        terms.setMaturityDate(request.maturityDate());
        terms.setCouponRate(request.couponRate());
        terms.setReferenceRate(request.referenceRate());
        terms.setSpread(request.spread());
        terms.setDayCount(request.dayCount());
        terms.setPaymentFrequency(request.paymentFrequency());
        terms.setCallable(request.callable());
        terms.setCallSchedule(request.callSchedule());
        if (terms.getBondStatus() == null) {
            terms.setBondStatus(BondStatus.ACTIVE);
        }

        return ResponseEntity.ok(bondTermsRepository.save(terms));
    }

    @GetMapping
    public ResponseEntity<AssetBondTerms> getBondTerms(@PathVariable UUID assetId) {
        return ResponseEntity.ok(
                bondTermsRepository.findById(assetId)
                        .orElseThrow(() -> new EntityNotFoundException("AssetBondTerms", assetId)));
    }
}
