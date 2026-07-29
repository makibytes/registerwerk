package de.makibytes.registerwerk.lending.web;

import de.makibytes.registerwerk.lending.internal.LendingPositionService;
import de.makibytes.registerwerk.lending.web.dto.LendingPositionResponse;
import de.makibytes.registerwerk.lending.web.dto.LendingSupplyPositionResponse;
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

    public LendingPositionController(LendingPositionService positionService) {
        this.positionService = positionService;
    }

    @GetMapping("/my-positions")
    public ResponseEntity<List<LendingPositionResponse>> myPositions(Authentication authentication) {
        UUID appUserId = extractAppUserId(authentication);
        List<LendingPositionResponse> positions = positionService.refreshAndListMyPositions(appUserId).stream()
                .map(p -> new LendingPositionResponse(
                        p.getMarketId(), p.getWalletAddress(), p.getCollateralAmount(), p.getCurrentDebt(),
                        p.getHealthFactorWad(), p.getHealthFactorReliable(), p.getStatus(), p.getLastSyncedAt()))
                .toList();
        return ResponseEntity.ok(positions);
    }

    @GetMapping("/supply-positions")
    public ResponseEntity<List<LendingSupplyPositionResponse>> supplyPositions(Authentication authentication) {
        UUID appUserId = extractAppUserId(authentication);
        List<LendingSupplyPositionResponse> positions = positionService.refreshAndListMySupplyPositions(appUserId)
                .stream()
                .map(p -> new LendingSupplyPositionResponse(
                        p.getMarketId(), p.getWalletAddress(), p.getCurrentClaim(), p.getLastSyncedAt()))
                .toList();
        return ResponseEntity.ok(positions);
    }

    private UUID extractAppUserId(Authentication authentication) {
        if (authentication == null) return null;
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
