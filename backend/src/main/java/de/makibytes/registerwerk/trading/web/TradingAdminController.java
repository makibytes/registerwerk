package de.makibytes.registerwerk.trading.web;

import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import de.makibytes.registerwerk.trading.internal.TradingService;
import de.makibytes.registerwerk.trading.web.dto.CancelTradeRequest;
import de.makibytes.registerwerk.trading.web.dto.TradeExecutionResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operator-only trade-settlement exception handling.
 * Base path: {@code /api/v1/admin/trading}
 *
 * <p>{@code refund} records that an already-SETTLED trade was reversed after the fact (e.g. a
 * compliance clawback) — see {@code TradingService.refundSettledTrade} for exactly what this
 * does and does NOT do (it does not itself reverse the on-chain transfer/cash leg).
 */
@RestController
@RequestMapping("/api/v1/admin/trading")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class TradingAdminController {

    private final TradingService tradingService;

    public TradingAdminController(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    @PostMapping("/history/{executionId}/refund")
    @RequiresStepUp(requireSecondApprover = true, reason = "TRADE_SETTLEMENT_REFUND")
    public ResponseEntity<TradeExecutionResponse> refund(
            @PathVariable UUID executionId,
            @Valid @RequestBody CancelTradeRequest request,
            Authentication auth,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        return ResponseEntity.ok(tradingService.refundSettledTrade(
                SecurityUtils.extractUserId(auth), executionId, request.reason(), approverId));
    }
}
