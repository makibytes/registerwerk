package de.makibytes.registerwerk.lending.web;

import de.makibytes.registerwerk.lending.internal.LenderEligibilityService;
import de.makibytes.registerwerk.lending.internal.LendingPositionService;
import de.makibytes.registerwerk.lending.web.dto.LendingPositionResponse;
import de.makibytes.registerwerk.lending.web.dto.LendingSupplyPositionResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The logged-in customer's own borrow/supply positions, refreshed live from chain on every
 * request across every wallet they've bound (see {@link LendingPositionService}).
 */
@RestController
@RequestMapping("/api/v1/lending")
@PreAuthorize("isAuthenticated()")
public class LendingPositionController {

    private final LendingPositionService positionService;

    private final LenderEligibilityService lenderEligibility;

    public LendingPositionController(LendingPositionService positionService, LenderEligibilityService lenderEligibility) {
        this.positionService = positionService;
        this.lenderEligibility = lenderEligibility;
    }

    /**
     * Preflight for the Supply &amp; Earn page (T2-20): whether the caller's entity may use the lender side.
     * {@code productionMode=false} means the gate is not enforced (demo); the page keeps its demo wording.
     */
    @GetMapping("/lender-eligibility")
    public ResponseEntity<LenderEligibilityService.Status> lenderEligibility(Authentication authentication) {
        return ResponseEntity.ok(lenderEligibility.status(SecurityUtils.extractEntityId(authentication)));
    }

    @GetMapping("/my-positions")
    public ResponseEntity<List<LendingPositionResponse>> myPositions(Authentication authentication) {
        UUID legalEntityId = SecurityUtils.extractEntityId(authentication);
        var shortfall = positionService.shortfallMarketIds();
        List<LendingPositionResponse> positions = positionService.refreshAndListMyPositions(legalEntityId).stream()
                .map(p -> new LendingPositionResponse(
                        p.getMarketId(), p.getWalletAddress(), p.getCollateralAmount(), p.getCurrentDebt(),
                        p.getHealthFactorWad(), p.getHealthFactorReliable(), p.getLiquidationSurplus(), p.getStatus(),
                        p.getLastSyncedAt(), p.isSyncStale(), p.getLastSyncError(),
                        shortfall.contains(p.getMarketId())))
                .toList();
        return ResponseEntity.ok(positions);
    }

    @GetMapping("/supply-positions")
    public ResponseEntity<List<LendingSupplyPositionResponse>> supplyPositions(Authentication authentication) {
        UUID legalEntityId = SecurityUtils.extractEntityId(authentication);
        // T2-20: in production mode the lender side needs the borrower gates (approved, screened, professional/ECP).
        lenderEligibility.requireLender(legalEntityId, "viewing and managing supply positions");
        List<LendingSupplyPositionResponse> positions = positionService.refreshAndListMySupplyPositions(legalEntityId)
                .stream()
                .map(p -> new LendingSupplyPositionResponse(
                        p.getMarketId(), p.getWalletAddress(), p.getCurrentClaim(), p.getLastSyncedAt()))
                .toList();
        return ResponseEntity.ok(positions);
    }

}
