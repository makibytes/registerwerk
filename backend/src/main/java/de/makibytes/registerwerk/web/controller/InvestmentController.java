package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.domain.asset.AssetHolder;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetHolderRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetRepository;
import de.makibytes.registerwerk.web.dto.InvestmentResponse;
import de.makibytes.registerwerk.web.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
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

    public InvestmentController(
            AssetHolderRepository assetHolderRepository,
            AssetRepository assetRepository) {
        this.assetHolderRepository = assetHolderRepository;
        this.assetRepository = assetRepository;
    }

    /**
     * Returns a paginated list of investments for the currently authenticated investor entity.
     */
    @GetMapping
    public ResponseEntity<PageResponse<InvestmentResponse>> getMyInvestments(
            Pageable pageable,
            Authentication auth) {
        UUID investorId = extractEntityId(auth);
        Page<AssetHolder> page = assetHolderRepository.findByInvestorId(investorId, pageable);
        Map<UUID, Asset> assetCache = loadAssetCache(page.getContent());
        return ResponseEntity.ok(PageResponse.of(page.map(h -> toResponse(h, assetCache.get(h.getAssetId())))));
    }

    /**
     * Returns a single investment record by holder ID.
     */
    @GetMapping("/{holderId}")
    public ResponseEntity<InvestmentResponse> getInvestment(
            @PathVariable UUID holderId,
            Authentication auth) {
        AssetHolder holder = assetHolderRepository.findById(holderId)
                .orElseThrow(() -> new EntityNotFoundException("AssetHolder", holderId));
        Asset asset = assetRepository.findById(holder.getAssetId()).orElse(null);
        return ResponseEntity.ok(toResponse(holder, asset));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private InvestmentResponse toResponse(AssetHolder h, Asset a) {
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
                h.getUpdatedAt()
        );
    }

    private Map<UUID, Asset> loadAssetCache(List<AssetHolder> holders) {
        Set<UUID> assetIds = holders.stream().map(AssetHolder::getAssetId).collect(Collectors.toSet());
        return assetRepository.findAllById(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, a -> a));
    }

    private UUID extractEntityId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String entityId = jwt.getClaimAsString("entity_id");
            if (entityId != null) {
                try {
                    return UUID.fromString(entityId);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        return null;
    }
}
