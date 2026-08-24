package de.makibytes.registerwerk.repo.web;

import de.makibytes.registerwerk.repo.api.RepoTypes.*;
import de.makibytes.registerwerk.repo.internal.RepoTradeService;
import de.makibytes.registerwerk.repo.internal.RepoTradeService.*;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/repo-desk/trades")
@PreAuthorize("hasAnyRole('TRADER', 'REGISTRY_ADMIN')")
public class RepoTradeController {
    private final RepoTradeService service;
    public RepoTradeController(RepoTradeService service){this.service=service;}

    @GetMapping public List<TradeResponse> list(Authentication auth){return service.list(entity(auth)).stream().map(this::response).toList();}
    @GetMapping("/{id}") public TradeResponse get(@PathVariable UUID id, Authentication auth){return response(service.get(id, entity(auth)));}

    @PostMapping("/{id}/open-settlement/{leg}")
    public TradeResponse confirmOpen(@PathVariable UUID id, @PathVariable SettlementLeg leg,
                                     @RequestBody @Valid SettlementConfirmation request, Authentication auth){
        return response(service.confirmOpenLeg(id, entity(auth), user(auth), leg, request.reference()));
    }

    @PostMapping("/{id}/margin-call")
    public TradeResponse marginCall(@PathVariable UUID id, @RequestBody @Valid MarginCallRequest request, Authentication auth){
        return response(service.issueMarginCall(id, entity(auth), user(auth), request.amount(), request.dueAt(), request.note()));
    }

    @PostMapping("/{id}/margin-call/satisfy")
    public TradeResponse satisfyMargin(@PathVariable UUID id, @RequestBody @Valid SettlementNote request, Authentication auth){
        return response(service.satisfyMarginCall(id, entity(auth), user(auth), request.reference(), request.note()));
    }

    @PostMapping("/{id}/substitution")
    public TradeResponse substitute(@PathVariable UUID id, @RequestBody @Valid SubstitutionRequest request, Authentication auth){
        return response(service.requestSubstitution(id, entity(auth), user(auth), request.assetId(), request.quantity(), request.note()));
    }

    @PostMapping("/{id}/substitution/decision")
    public TradeResponse decideSubstitution(@PathVariable UUID id, @RequestBody @Valid DecisionRequest request, Authentication auth){
        return response(service.decideSubstitution(id, entity(auth), user(auth), request.approve(), request.note()));
    }

    @PostMapping("/{id}/close")
    public TradeResponse initiateClose(@PathVariable UUID id, Authentication auth){
        return response(service.initiateClose(id, entity(auth), user(auth)));
    }

    @PostMapping("/{id}/close-settlement/{leg}")
    public TradeResponse confirmClose(@PathVariable UUID id, @PathVariable SettlementLeg leg,
                                      @RequestBody @Valid SettlementConfirmation request, Authentication auth){
        return response(service.confirmCloseLeg(id, entity(auth), user(auth), leg, request.reference()));
    }

    @PostMapping("/{id}/default")
    public TradeResponse declareDefault(@PathVariable UUID id, @RequestBody @Valid NoteRequest request, Authentication auth){
        return response(service.declareDefault(id, entity(auth), user(auth), request.note()));
    }

    private UUID entity(Authentication auth){return SecurityUtils.extractEntityId(auth);}
    private UUID user(Authentication auth){return SecurityUtils.extractUserId(auth);}
    private TradeResponse response(TradeView view){
        var t=view.trade();
        return new TradeResponse(t.getId(),t.getRfqId(),t.getAcceptedQuoteId(),t.getStatus(),
                t.getCashBorrowerEntityId(),view.borrowerName(),t.getCashLenderEntityId(),view.lenderName(),
                t.getCollateralAssetId(),view.collateralName(),view.collateralIsin(),t.getCollateralQuantity(),
                t.getCashAmount(),t.getCashCurrency(),t.getRepoRate(),t.getHaircutBps(),t.getStartDate(),t.getEndDate(),
                t.getRepurchaseAmount(),t.getSettlementMethod(),t.isOpenCashConfirmed(),t.isOpenCollateralConfirmed(),
                t.isCloseCashConfirmed(),t.isCloseCollateralConfirmed(),t.getMarginCallAmount(),t.getMarginCallDueAt(),
                t.getPendingSubstitutionAssetId(),t.getPendingSubstitutionQuantity(),view.borrower(),
                view.events().stream().map(this::event).toList(),t.getCreatedAt(),t.getUpdatedAt());
    }
    private EventResponse event(EventView view){var e=view.event();return new EventResponse(e.getId(),e.getEventType(),
            e.getActorEntityId(),view.actorName(),e.getAmount(),e.getAssetId(),e.getQuantity(),e.getReference(),e.getNote(),e.getCreatedAt());}

    public record SettlementConfirmation(@NotBlank @Size(max=200) String reference){}
    public record MarginCallRequest(@NotNull @DecimalMin(value="0",inclusive=false) BigDecimal amount,
                                    @NotNull @Future Instant dueAt,@Size(max=1000) String note){}
    public record SettlementNote(@NotBlank @Size(max=200) String reference,@Size(max=1000) String note){}
    public record SubstitutionRequest(@NotNull UUID assetId,
                                      @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal quantity,
                                      @Size(max=1000) String note){}
    public record DecisionRequest(boolean approve,@Size(max=1000) String note){}
    public record NoteRequest(@NotBlank @Size(max=1000) String note){}
    public record TradeResponse(UUID id,UUID rfqId,UUID acceptedQuoteId,TradeStatus status,
            UUID cashBorrowerEntityId,String cashBorrowerName,UUID cashLenderEntityId,String cashLenderName,
            UUID collateralAssetId,String collateralAssetName,String collateralIsin,BigDecimal collateralQuantity,
            BigDecimal cashAmount,String cashCurrency,BigDecimal repoRate,int haircutBps,LocalDate startDate,
            LocalDate endDate,BigDecimal repurchaseAmount,SettlementMethod settlementMethod,
            boolean openCashConfirmed,boolean openCollateralConfirmed,boolean closeCashConfirmed,
            boolean closeCollateralConfirmed,BigDecimal marginCallAmount,Instant marginCallDueAt,
            UUID pendingSubstitutionAssetId,BigDecimal pendingSubstitutionQuantity,boolean borrower,
            List<EventResponse> events,Instant createdAt,Instant updatedAt){}
    public record EventResponse(UUID id,LifecycleEventType type,UUID actorEntityId,String actorName,
            BigDecimal amount,UUID assetId,BigDecimal quantity,String reference,String note,Instant createdAt){}
}

