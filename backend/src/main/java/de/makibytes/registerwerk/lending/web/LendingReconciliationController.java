package de.makibytes.registerwerk.lending.web;

import de.makibytes.registerwerk.idempotency.api.RequiresIdempotencyKey;
import de.makibytes.registerwerk.lending.internal.LendingMarketService;
import de.makibytes.registerwerk.lending.internal.LendingReconciliationService;
import de.makibytes.registerwerk.lending.web.dto.BorrowPauseRequest;
import de.makibytes.registerwerk.lending.web.dto.LendingReconciliationTaskResponse;
import de.makibytes.registerwerk.lending.web.dto.ReconcileCollateralRequest;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operator controls for collateral that left a lending market outside repay/liquidate (5B-10,
 * parked T5-12 interim): task queue, on-chain borrow pause and the reconcileCollateral action.
 */
@RestController
@RequestMapping("/api/v1/lending")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
@RequiresIdempotencyKey
public class LendingReconciliationController {

    private final LendingReconciliationService reconciliationService;
    private final LendingMarketService marketService;

    public LendingReconciliationController(
            LendingReconciliationService reconciliationService, LendingMarketService marketService) {
        this.reconciliationService = reconciliationService;
        this.marketService = marketService;
    }

    @GetMapping("/reconciliation-tasks")
    public ResponseEntity<List<LendingReconciliationTaskResponse>> tasks() {
        return ResponseEntity.ok(reconciliationService.listUnresolvedTasks().stream()
                .map(t -> LendingReconciliationTaskResponse.of(t,
                        marketService.requireMarket(t.getMarketId()).getMarketAddress()))
                .toList());
    }

    @PostMapping("/markets/{marketId}/reconcile-collateral")
    @RequiresStepUp(reason = "Lending collateral reconciliation", requireSecondApprover = true)
    public ResponseEntity<LendingReconciliationTaskResponse> reconcile(
            @PathVariable UUID marketId, @RequestBody @Valid ReconcileCollateralRequest request,
            Authentication authentication) {
        var task = reconciliationService.reconcileCollateral(marketId, request.borrowerWallet(),
                request.attributableCollateral(), request.forcedTransferRef(), request.legalBasis(),
                actorId(authentication));
        return ResponseEntity.accepted().body(LendingReconciliationTaskResponse.of(task,
                marketService.requireMarket(marketId).getMarketAddress()));
    }

    @PostMapping("/markets/{marketId}/borrow-paused")
    @RequiresStepUp(reason = "Lending borrow pause", requireSecondApprover = true)
    public ResponseEntity<Map<String, String>> setBorrowPaused(
            @PathVariable UUID marketId, @RequestBody @Valid BorrowPauseRequest request,
            Authentication authentication) {
        String txHash = reconciliationService.setBorrowPaused(
                marketId, request.paused(), request.reason(), actorId(authentication));
        return ResponseEntity.accepted().body(Map.of("txHash", txHash));
    }

    /** H11: submits setBorrowPaused(true) for every unverified / legacy market that is not yet paused on-chain. */
    @PostMapping("/markets/legacy-borrow-pause")
    @RequiresStepUp(reason = "Lending legacy market borrow pause", requireSecondApprover = true)
    public ResponseEntity<List<LendingReconciliationService.PauseEnforcement>> enforceLegacyBorrowPause(
            Authentication authentication) {
        return ResponseEntity.accepted().body(reconciliationService.enforceLegacyBorrowPause(actorId(authentication)));
    }

    private static UUID actorId(Authentication authentication) {
        try {
            return authentication == null ? null : UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
