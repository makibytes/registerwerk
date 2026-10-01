package de.makibytes.registerwerk.repo.web;

import de.makibytes.registerwerk.repo.api.RepoTypes.DisputeResolution;
import de.makibytes.registerwerk.repo.internal.RepoTradeService;
import de.makibytes.registerwerk.repo.internal.RepoTradeService.TradeView;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operator queue for disputed repo trades (T5-09 interim). The operator records an outcome under a legal
 * basis (step-up + second approver); it does not decide the merits of the dispute.
 */
@RestController
@RequestMapping("/api/v1/admin/repo")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class RepoDisputeAdminController {
    private final RepoTradeService service;

    public RepoDisputeAdminController(RepoTradeService service) { this.service = service; }

    @GetMapping("/disputes")
    public List<DisputeResponse> disputes() {
        return service.disputes().stream().map(this::response).toList();
    }

    @PostMapping("/disputes/{tradeId}/resolve")
    @RequiresStepUp(requireSecondApprover = true, reason = "REPO_DISPUTE_RESOLVE")
    public DisputeResponse resolve(@PathVariable UUID tradeId, @Valid @RequestBody ResolveRequest request, Authentication auth,
                                   @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        return response(service.resolveDispute(tradeId, SecurityUtils.extractUserId(auth), request.resolution(),
                request.legalBasis(), request.note(), approverId));
    }

    private DisputeResponse response(TradeView view) {
        var t = view.trade();
        return new DisputeResponse(t.getId(), t.getStatus(), view.borrowerName(), view.lenderName(), view.collateralName(),
                view.collateralIsin(), t.getCollateralQuantity(), t.getCashAmount(), t.getCashCurrency(), t.getRepurchaseAmount(),
                t.getDisputeReason(), t.getPreDisputeStatus(), t.getDisputedAt(), t.getUti(),
                view.events().stream().map(e -> new Entry(e.event().getEventType().name(), e.actorName(),
                        e.event().getReference(), e.event().getNote(), e.event().getCreatedAt())).toList());
    }

    public record ResolveRequest(@NotNull DisputeResolution resolution, @NotBlank @Size(max = 500) String legalBasis,
                                 @Size(max = 2000) String note) {}
    public record Entry(String type, String actor, String reference, String note, Instant at) {}
    public record DisputeResponse(UUID id, Object status, String borrower, String lender, String collateral, String isin,
                                  BigDecimal collateralQuantity, BigDecimal cashAmount, String currency,
                                  BigDecimal repurchaseAmount, String disputeReason, Object preDisputeStatus,
                                  Instant disputedAt, String uti, List<Entry> events) {}
}
