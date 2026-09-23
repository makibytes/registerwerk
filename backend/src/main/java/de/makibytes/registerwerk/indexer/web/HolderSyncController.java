package de.makibytes.registerwerk.indexer.web;

import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.indexer.api.HolderDataService;
import de.makibytes.registerwerk.indexer.api.UnmappedHolderIdentityException;
import de.makibytes.registerwerk.indexer.internal.NomineePoolHolderService;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Holder-sync operator surface (T2-18).
 *
 * <ul>
 *   <li>Manual holder refresh — moved here from {@code asset.web.HolderController} (same path and
 *       authorization): the {@code asset -> indexer} import it needed would close a module cycle
 *       with {@code indexer -> lending -> asset} now that the indexer reacts to lending-market
 *       registrations.</li>
 *   <li>"Register pool address": enters a pool contract — DvP escrow, desk inventory, facility, or
 *       a lending market registered before this existed — into an asset's register as a
 *       nominee-pool holder, clearing a BLOCKED holder sync on the next refresh.</li>
 * </ul>
 */
@RestController
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class HolderSyncController {

    private static final Logger log = LoggerFactory.getLogger(HolderSyncController.class);

    public record RegisterPoolRequest(
            @NotBlank @Size(max = 66) String walletAddress,
            @NotBlank String poolKind,
            UUID investorId) {}

    public record NomineePoolResponse(UUID holderId, UUID assetId, String walletAddress, UUID investorId,
                                      BigDecimal nominalAmount) {
        static NomineePoolResponse of(AssetHolder h) {
            return new NomineePoolResponse(h.getId(), h.getAssetId(), h.getWalletAddress(), h.getInvestorId(),
                    h.getNominalAmount());
        }
    }

    private final NomineePoolHolderService service;
    private final HolderDataService holderDataService;

    public HolderSyncController(NomineePoolHolderService service, HolderDataService holderDataService) {
        this.service = service;
        this.holderDataService = holderDataService;
    }

    /**
     * Manually refresh holder data from the indexed chain history for one asset. A refusal because
     * of unmapped wallets is a 409 carrying the wallets — the asset is then persisted as BLOCKED.
     */
    @PostMapping("/api/v1/assets/{assetId}/holders/refresh")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<Map<String, Object>> refreshAssetHolders(@PathVariable UUID assetId) {
        try {
            holderDataService.manualRefreshIssuance(assetId.toString());
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Holder data refresh initiated for asset " + assetId));
        } catch (UnmappedHolderIdentityException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status", "blocked",
                    "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to refresh holders for asset {}: {}", assetId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "error",
                    "message", "Failed to refresh holder data: " + e.getMessage()));
        }
    }

    @PostMapping("/api/v1/assets/{assetId}/nominee-pools")
    public ResponseEntity<NomineePoolResponse> register(@PathVariable UUID assetId,
                                                        @Valid @RequestBody RegisterPoolRequest request,
                                                        Authentication auth) {
        AssetHolder holder = service.register(assetId, request.walletAddress(), request.poolKind(),
                request.investorId(), SecurityUtils.extractUserId(auth),
                SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(NomineePoolResponse.of(holder));
    }

    /** Backfill: every registered lending market's contract as a nominee pool of its collateral asset. */
    @PostMapping("/api/v1/indexers/nominee-pools/backfill-lending-markets")
    public ResponseEntity<Map<String, Object>> backfillLendingMarkets(Authentication auth) {
        int registered = service.backfillLendingMarkets(SecurityUtils.extractUserId(auth),
                SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(Map.of("registered", registered,
                "nomineeEntityConfigured", service.defaultNomineeEntityId().isPresent()));
    }
}
