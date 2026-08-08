package de.makibytes.registerwerk.trading.web;

import de.makibytes.registerwerk.externalref.ExternalRefApi;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.customer.api.ExternalReferenceSubjectType;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.trading.web.dto.InvestmentResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.shared.api.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for investor-facing investment views.
 * Exposes enriched holding records that include resolved asset metadata.
 */
@RestController
@RequestMapping("/api/v1/investments")
@PreAuthorize("hasAnyRole('INVESTOR', 'REGISTRY_ADMIN')")
public class InvestmentController {

    private final AssetHolderRepository assetHolderRepository;
    private final AssetRepository assetRepository;
    private final ExternalRefApi companyExternalReferenceService;

    public InvestmentController(
            AssetHolderRepository assetHolderRepository,
            AssetRepository assetRepository,
            ExternalRefApi companyExternalReferenceService) {
        this.assetHolderRepository = assetHolderRepository;
        this.assetRepository = assetRepository;
        this.companyExternalReferenceService = companyExternalReferenceService;
    }

    /**
     * Returns a paginated list of investments for the currently authenticated investor entity.
     */
    @GetMapping
    public ResponseEntity<PageResponse<InvestmentResponse>> getMyInvestments(
            Pageable pageable,
            Authentication auth) {
        UUID investorId = SecurityUtils.extractEntityId(auth);
        if (investorId == null) {
            throw new AccessDeniedException("An investor entity context is required");
        }
        // A removed holder no longer holds the position and must not appear in the customer's list.
        Page<AssetHolder> page = assetHolderRepository.findActiveByInvestorId(investorId, pageable);
        Map<UUID, Asset> assetCache = loadAssetCache(page.getContent());
        return ResponseEntity.ok(PageResponse.of(page.map(h -> toResponse(auth, h, assetCache.get(h.getAssetId())))));
    }

    /**
     * Returns a single investment record by holder ID.
     */
    @GetMapping("/{holderId}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @investmentAccessChecker.canRead(#holderId, authentication)")
    public ResponseEntity<InvestmentResponse> getInvestment(
            @PathVariable UUID holderId,
            Authentication auth) {
        AssetHolder holder = assetHolderRepository.findById(holderId)
                .orElseThrow(() -> new EntityNotFoundException("AssetHolder", holderId));
        Asset asset = assetRepository.findById(holder.getAssetId()).orElse(null);
        return ResponseEntity.ok(toResponse(auth, holder, asset));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private InvestmentResponse toResponse(Authentication authentication, AssetHolder h, Asset a) {
        return new InvestmentResponse(
                h.getId(),
                h.getAssetId(),
                a != null ? a.getAssetNumber() : null,
                a != null ? a.getName() : null,
                a != null ? a.getIsin() : null,
                a != null ? a.getTokenStandard() : null,
                a != null ? a.getStatus() : null,
                h.getInvestorId(),
                h.getWalletAddress(),
                h.getWhitelisted(),
                h.getWhitelistTxHash(),
                h.getNominalAmount(),
                h.getAcquisitionDate(),
                h.getCreatedAt(),
                h.getUpdatedAt(),
                companyExternalReferenceService
                        .findExternalId(authentication, ExternalReferenceSubjectType.ASSET_HOLDER, h.getId())
                        .orElse(null),
                a != null ? a.getChain() : null,
                a != null ? a.getCurrency() : null,
                a != null ? a.getDenomination() : null
        );
    }

    private Map<UUID, Asset> loadAssetCache(List<AssetHolder> holders) {
        Set<UUID> assetIds = holders.stream().map(AssetHolder::getAssetId).collect(Collectors.toSet());
        return assetRepository.findAllById(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, a -> a));
    }

}
