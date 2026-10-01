package de.makibytes.registerwerk.trading.web;

import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import de.makibytes.registerwerk.trading.internal.OrderHistoryExportService;
import de.makibytes.registerwerk.trading.internal.TradeQueueService;
import de.makibytes.registerwerk.trading.internal.TradingService;
import de.makibytes.registerwerk.trading.web.dto.AddTradeNoteRequest;
import de.makibytes.registerwerk.trading.web.dto.CancelTradeRequest;
import de.makibytes.registerwerk.trading.web.dto.ResolveUnresolvedTradeRequest;
import de.makibytes.registerwerk.trading.web.dto.TimeoutBacklogResponse;
import de.makibytes.registerwerk.trading.web.dto.TradeExecutionResponse;
import de.makibytes.registerwerk.trading.web.dto.TradeNoteResponse;
import de.makibytes.registerwerk.trading.web.dto.UnresolvedTradeResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Operator-only trade-settlement exception handling.
 * Base path: {@code /api/v1/admin/trading}
 *
 * <p>{@code refund} records that an already-SETTLED trade was reversed after the fact (e.g. a
 * compliance clawback) — see {@code TradingService.refundSettledTrade} for exactly what this
 * does and does NOT do (it does not itself reverse the on-chain transfer/cash leg).
 *
 * <p>The unresolved-payment queue (Phase 5, 5A-03) lists trades whose declared payment neither
 * settled nor failed (seller dispute, seller-confirmation timeout, a gate failing at confirm
 * time, register entry / asset / party becoming ineligible). Every action is step-up + second
 * approver and needs a legal basis; the operator records evidence, not the merits of the dispute.
 */
@RestController
@RequestMapping("/api/v1/admin/trading")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class TradingAdminController {

    private final TradingService tradingService;
    private final TradeQueueService queueService;
    private final OrderHistoryExportService orderHistoryService;

    public TradingAdminController(TradingService tradingService, TradeQueueService queueService,
                                  OrderHistoryExportService orderHistoryService) {
        this.tradingService = tradingService;
        this.queueService = queueService;
        this.orderHistoryService = orderHistoryService;
    }

    /**
     * Order/execution record export (CSV) for a market-abuse tool (5C-06 interim). Window defaults to
     * the last 30 days, capped at 366 days. Interim scope: not a validated RTS 22 report.
     */
    @GetMapping(value = "/order-history", produces = "text/csv")
    public ResponseEntity<byte[]> orderHistory(
            @RequestParam(required = false) java.time.Instant from,
            @RequestParam(required = false) java.time.Instant to) {
        java.time.Instant end = to != null ? to : java.time.Instant.now().plusSeconds(1);
        java.time.Instant start = from != null ? from : end.minus(java.time.Duration.ofDays(30));
        if (!start.isBefore(end) || java.time.Duration.between(start, end).toDays() > 366) {
            throw new IllegalArgumentException("Invalid window: from must precede to and span at most 366 days");
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"trading-order-history.csv\"")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(orderHistoryService.exportCsv(start, end));
    }

    @GetMapping("/unresolved")
    public ResponseEntity<List<UnresolvedTradeResponse>> unresolved() {
        return ResponseEntity.ok(queueService.listUnresolved());
    }

    /** SRE report: what the next timeout run will move, plus historic FAILED-after-declared-payment candidates. */
    @GetMapping("/timeout-backlog")
    public ResponseEntity<TimeoutBacklogResponse> timeoutBacklog() {
        return ResponseEntity.ok(queueService.backlog());
    }

    @PostMapping("/unresolved/{executionId}/force-settle")
    @RequiresStepUp(requireSecondApprover = true, reason = "TRADE_UNRESOLVED_FORCE_SETTLE")
    public ResponseEntity<TradeExecutionResponse> forceSettle(
            @PathVariable UUID executionId,
            @Valid @RequestBody ResolveUnresolvedTradeRequest request,
            Authentication auth,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        return ResponseEntity.ok(tradingService.forceSettleUnresolved(
                SecurityUtils.extractUserId(auth), executionId, request.legalBasis(), request.note(), approverId));
    }

    @PostMapping("/unresolved/{executionId}/record-return-of-funds")
    @RequiresStepUp(requireSecondApprover = true, reason = "TRADE_UNRESOLVED_RETURN_OF_FUNDS")
    public ResponseEntity<TradeExecutionResponse> recordReturnOfFunds(
            @PathVariable UUID executionId,
            @Valid @RequestBody ResolveUnresolvedTradeRequest request,
            Authentication auth,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        return ResponseEntity.ok(tradingService.recordReturnOfFunds(
                SecurityUtils.extractUserId(auth), executionId, request.legalBasis(), request.note(), approverId));
    }

    @PostMapping("/unresolved/{executionId}/release")
    @RequiresStepUp(requireSecondApprover = true, reason = "TRADE_UNRESOLVED_RELEASE")
    public ResponseEntity<TradeExecutionResponse> release(
            @PathVariable UUID executionId,
            @Valid @RequestBody ResolveUnresolvedTradeRequest request,
            Authentication auth,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        return ResponseEntity.ok(tradingService.releaseUnresolved(
                SecurityUtils.extractUserId(auth), executionId, request.legalBasis(), request.note(), approverId));
    }

    @PostMapping("/history/{executionId}/notes")
    public ResponseEntity<TradeNoteResponse> addNote(
            @PathVariable UUID executionId,
            @Valid @RequestBody AddTradeNoteRequest request,
            Authentication auth) {
        return ResponseEntity.ok(queueService.addNote(
                null, SecurityUtils.extractUserId(auth), "REGISTRY_ADMIN", executionId, request.text()));
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
