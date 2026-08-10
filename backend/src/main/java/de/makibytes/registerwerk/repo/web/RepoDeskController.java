package de.makibytes.registerwerk.repo.web;

import de.makibytes.registerwerk.repo.api.RepoTypes.*;
import de.makibytes.registerwerk.repo.internal.RepoDeskService;
import de.makibytes.registerwerk.repo.internal.RepoDeskService.*;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/repo-desk")
@PreAuthorize("hasAnyRole('TRADER', 'REGISTRY_ADMIN')")
public class RepoDeskController {
    private final RepoDeskService service;

    public RepoDeskController(RepoDeskService service) { this.service = service; }

    @GetMapping("/rfqs")
    public List<RfqResponse> list(Authentication auth) {
        return service.listVisible(SecurityUtils.extractEntityId(auth)).stream().map(this::response).toList();
    }

    @GetMapping("/rfqs/{rfqId}")
    public RfqResponse get(@PathVariable UUID rfqId, Authentication auth) {
        return response(service.get(rfqId, SecurityUtils.extractEntityId(auth)));
    }

    @PostMapping("/rfqs")
    @ResponseStatus(HttpStatus.CREATED)
    public RfqResponse create(@RequestBody @Valid CreateRfqRequest request, Authentication auth) {
        return response(service.create(SecurityUtils.extractEntityId(auth), SecurityUtils.extractUserId(auth),
                new CreateRfq(request.side(), request.visibility(), request.collateralAssetId(),
                        request.collateralQuantity(), request.cashAmount(), request.cashCurrency(),
                        request.startDate(), request.endDate(), request.proposedRepoRate(),
                        request.proposedHaircutBps(), request.settlementMethod(), request.expiresAt(),
                        request.targetEntityIds(), request.notes())));
    }

    @PostMapping("/rfqs/{rfqId}/cancel")
    public RfqResponse cancel(@PathVariable UUID rfqId, Authentication auth) {
        return response(service.cancel(rfqId, SecurityUtils.extractEntityId(auth)));
    }

    @PutMapping("/rfqs/{rfqId}/quote")
    public RfqResponse quote(@PathVariable UUID rfqId, @RequestBody @Valid SubmitQuoteRequest request,
                             Authentication auth) {
        return response(service.submitQuote(rfqId, SecurityUtils.extractEntityId(auth),
                SecurityUtils.extractUserId(auth), new SubmitQuote(request.cashAmount(), request.repoRate(),
                        request.haircutBps(), request.validUntil(), request.message())));
    }

    @DeleteMapping("/rfqs/{rfqId}/quote")
    public RfqResponse withdrawQuote(@PathVariable UUID rfqId, Authentication auth) {
        return response(service.withdrawQuote(rfqId, SecurityUtils.extractEntityId(auth)));
    }

    @PostMapping("/rfqs/{rfqId}/quotes/{quoteId}/accept")
    public RfqResponse acceptQuote(@PathVariable UUID rfqId, @PathVariable UUID quoteId, Authentication auth) {
        return response(service.acceptQuote(rfqId, quoteId, SecurityUtils.extractEntityId(auth)));
    }

    @GetMapping("/counterparties")
    public List<CounterpartyView> counterparties(Authentication auth) {
        return service.counterparties(SecurityUtils.extractEntityId(auth));
    }

    @GetMapping("/collateral")
    public List<CollateralView> collateral() { return service.collateral(); }

    private RfqResponse response(RfqView view) {
        var rfq = view.rfq();
        return new RfqResponse(rfq.getId(), rfq.getSide(), rfq.getVisibility(), rfq.getStatus(),
                rfq.getRequesterEntityId(), view.requesterName(), rfq.getCollateralAssetId(),
                view.collateralAssetName(), view.collateralIsin(), rfq.getCollateralQuantity(),
                rfq.getCashAmount(), rfq.getCashCurrency(), rfq.getStartDate(), rfq.getEndDate(),
                rfq.getProposedRepoRate(), rfq.getProposedHaircutBps(), rfq.getSettlementMethod(),
                rfq.getExpiresAt(), view.targetEntityIds(), rfq.getNotes(), view.mine(), view.canQuote(),
                view.tradeId(), view.quotes().stream().map(this::quoteResponse).toList(),
                rfq.getCreatedAt(), rfq.getUpdatedAt());
    }

    private QuoteResponse quoteResponse(QuoteView view) {
        var quote = view.quote();
        return new QuoteResponse(quote.getId(), quote.getQuotingEntityId(), view.quotingEntityName(),
                quote.getCashAmount(), quote.getRepoRate(), quote.getHaircutBps(), quote.getValidUntil(),
                quote.getStatus(), quote.getMessage(), quote.getCreatedAt());
    }

    public record CreateRfqRequest(
            @NotNull Side side,
            @NotNull Visibility visibility,
            @NotNull UUID collateralAssetId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal collateralQuantity,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal cashAmount,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String cashCurrency,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @DecimalMin("0") BigDecimal proposedRepoRate,
            @Min(0) @Max(10000) Integer proposedHaircutBps,
            @NotNull SettlementMethod settlementMethod,
            @NotNull @Future Instant expiresAt,
            @Size(max = 25) Set<UUID> targetEntityIds,
            @Size(max = 1000) String notes) {}

    public record SubmitQuoteRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal cashAmount,
            @NotNull @DecimalMin("0") BigDecimal repoRate,
            @Min(0) @Max(10000) int haircutBps,
            @NotNull @Future Instant validUntil,
            @Size(max = 500) String message) {}

    public record RfqResponse(UUID id, Side side, Visibility visibility, RfqStatus status,
                              UUID requesterEntityId, String requesterName, UUID collateralAssetId,
                              String collateralAssetName, String collateralIsin,
                              BigDecimal collateralQuantity, BigDecimal cashAmount, String cashCurrency,
                              LocalDate startDate, LocalDate endDate, BigDecimal proposedRepoRate,
                              Integer proposedHaircutBps, SettlementMethod settlementMethod,
                              Instant expiresAt, Set<UUID> targetEntityIds, String notes,
                              boolean mine, boolean canQuote, UUID tradeId, List<QuoteResponse> quotes,
                              Instant createdAt, Instant updatedAt) {}

    public record QuoteResponse(UUID id, UUID quotingEntityId, String quotingEntityName,
                                BigDecimal cashAmount, BigDecimal repoRate, int haircutBps,
                                Instant validUntil, QuoteStatus status, String message, Instant createdAt) {}
}
