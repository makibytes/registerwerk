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

    @PostMapping("/{id}/open-settlement/{leg}/declare")
    public TradeResponse declareOpen(@PathVariable UUID id, @PathVariable SettlementLeg leg,
                                     @RequestBody @Valid SettlementConfirmation request, Authentication auth){
        return response(service.declareLegSent(id, entity(auth), user(auth), Phase.OPEN, leg, request.reference()));
    }

    @PostMapping("/{id}/close-settlement/{leg}/declare")
    public TradeResponse declareClose(@PathVariable UUID id, @PathVariable SettlementLeg leg,
                                      @RequestBody @Valid SettlementConfirmation request, Authentication auth){
        return response(service.declareLegSent(id, entity(auth), user(auth), Phase.CLOSE, leg, request.reference()));
    }

    @PostMapping("/{id}/margin-call")
    public TradeResponse marginCall(@PathVariable UUID id, @RequestBody @Valid MarginCallRequest request, Authentication auth){
        return response(service.issueMarginCall(id, entity(auth), user(auth), request.amount(), request.dueAt(),
                request.valuationReference(), request.valuationAmount(), request.note()));
    }

    /** Borrower: the top-up was sent (does not clear the call). */
    @PostMapping("/{id}/margin-call/delivered")
    public TradeResponse marginDelivered(@PathVariable UUID id, @RequestBody @Valid SettlementNote request, Authentication auth){
        return response(service.declareMarginDelivered(id, entity(auth), user(auth), request.reference(), request.note()));
    }

    /** Lender: the top-up arrived - clears the call. */
    @PostMapping("/{id}/margin-call/confirm")
    public TradeResponse marginConfirm(@PathVariable UUID id, @RequestBody @Valid SettlementNote request, Authentication auth){
        return response(service.confirmMarginReceived(id, entity(auth), user(auth), request.reference(), request.note()));
    }

    @PostMapping("/{id}/substitution")
    public TradeResponse substitute(@PathVariable UUID id, @RequestBody @Valid SubstitutionRequest request, Authentication auth){
        return response(service.requestSubstitution(id, entity(auth), user(auth), request.assetId(), request.quantity(), request.note()));
    }

    @PostMapping("/{id}/substitution/{requestId}/decision")
    public TradeResponse decideSubstitution(@PathVariable UUID id, @PathVariable UUID requestId,
                                            @RequestBody @Valid DecisionRequest request, Authentication auth){
        return response(service.decideSubstitution(id, requestId, entity(auth), user(auth), request.approve(), request.note()));
    }

    @PostMapping("/{id}/substitution/withdraw")
    public TradeResponse withdrawSubstitution(@PathVariable UUID id, @RequestBody @Valid NoteOptional request, Authentication auth){
        return response(service.withdrawSubstitution(id, entity(auth), user(auth), request.note()));
    }

    @PostMapping("/{id}/substitution/{leg}")
    public TradeResponse confirmSubstitution(@PathVariable UUID id, @PathVariable SubstitutionLeg leg,
                                             @RequestBody @Valid SettlementConfirmation request, Authentication auth){
        return response(service.confirmSubstitutionLeg(id, entity(auth), user(auth), leg, request.reference()));
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

    /** Step 1 of a default: notice; starts the grace period. */
    @PostMapping("/{id}/default-notice")
    public TradeResponse defaultNotice(@PathVariable UUID id, @RequestBody @Valid NoteOptional request, Authentication auth){
        return response(service.serveDefaultNotice(id, entity(auth), user(auth), request.note()));
    }

    /** Step 2: declaration, only after the grace period and only while the obligation is unmet. */
    @PostMapping("/{id}/default")
    public TradeResponse declareDefault(@PathVariable UUID id, @RequestBody @Valid NoteRequest request, Authentication auth){
        return response(service.declareDefault(id, entity(auth), user(auth), request.note()));
    }

    @PostMapping("/{id}/dispute")
    public TradeResponse dispute(@PathVariable UUID id, @RequestBody @Valid NoteRequest request, Authentication auth){
        return response(service.openDispute(id, entity(auth), user(auth), request.note()));
    }

    @PostMapping("/{id}/notes")
    public TradeResponse note(@PathVariable UUID id, @RequestBody @Valid NoteRequest request, Authentication auth){
        return response(service.addNote(id, entity(auth), user(auth), request.note()));
    }

    /** Art. 4 SFTR fields held for the parties' own reporting; Registerwerk does not report. */
    @GetMapping("/{id}/sftr-fields")
    public SftrFields sftrFields(@PathVariable UUID id, Authentication auth){
        return service.sftrFields(id, entity(auth));
    }

    private UUID entity(Authentication auth){return SecurityUtils.extractEntityId(auth);}
    private UUID user(Authentication auth){return SecurityUtils.extractUserId(auth);}
    private TradeResponse response(TradeView view){
        var t=view.trade();
        var liveSub=view.substitutions().stream().filter(r->r.getStatus()==SubstitutionStatus.PENDING||r.getStatus()==SubstitutionStatus.APPROVED).findFirst();
        return new TradeResponse(t.getId(),t.getRfqId(),t.getAcceptedQuoteId(),t.getStatus(),
                t.getCashBorrowerEntityId(),view.borrowerName(),t.getCashLenderEntityId(),view.lenderName(),
                t.getCollateralAssetId(),view.collateralName(),view.collateralIsin(),t.getCollateralQuantity(),
                t.getCashAmount(),t.getCashCurrency(),t.getRepoRate(),t.getHaircutBps(),t.getStartDate(),t.getEndDate(),
                t.getRepurchaseAmount(),t.getSettlementMethod(),t.isOpenCashConfirmed(),t.isOpenCollateralConfirmed(),
                t.isCloseCashConfirmed(),t.isCloseCollateralConfirmed(),t.getMarginCallAmount(),t.getMarginCallDueAt(),
                liveSub.map(r->r.getAssetId()).orElse(null),liveSub.map(r->r.getQuantity()).orElse(null),view.borrower(),
                view.events().stream().map(this::event).toList(),t.getCreatedAt(),t.getUpdatedAt(),
                t.getTermsHash(),t.getAcceptedQuoteVersion(),t.getDayCountBasis(),t.getUti(),t.getVenue(),t.isCollateralReuseConsent(),
                t.getOpenCashDeclaredAt(),t.getOpenCollateralDeclaredAt(),t.getCloseCashDeclaredAt(),t.getCloseCollateralDeclaredAt(),
                t.getMarginValuationReference(),t.getMarginValuationAmount(),t.getMarginHaircutBps(),t.getMarginDeliveredAt(),
                t.getDefaultNoticeAt(),t.getDefaultNoticeGround(),t.getDefaultGround(),t.getDefaultingPartyEntityId(),
                t.getDisputeReason(),t.getPreDisputeStatus(),t.getDisputedAt(),
                view.substitutions().stream().map(r->new SubstitutionResponse(r.getId(),r.getAssetId(),r.getQuantity(),r.getStatus(),
                        r.getRequestedBy(),r.getRequestedAt(),r.getDecidedAt(),r.getReplacementReceivedAt(),r.getOriginalReturnedAt(),r.getCompletedAt(),r.getNote())).toList());
    }
    private EventResponse event(EventView view){var e=view.event();return new EventResponse(e.getId(),e.getEventType(),
            e.getActorEntityId(),view.actorName(),e.getAmount(),e.getAssetId(),e.getQuantity(),e.getReference(),e.getNote(),e.getCreatedAt());}

    public record SettlementConfirmation(@NotBlank @Size(max=200) String reference){}
    public record MarginCallRequest(@NotNull @DecimalMin(value="0",inclusive=false) BigDecimal amount,
                                    @NotNull @Future Instant dueAt,
                                    @NotBlank @Size(max=200) String valuationReference,
                                    @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal valuationAmount,
                                    @Size(max=1000) String note){}
    public record NoteOptional(@Size(max=1000) String note){}
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
            List<EventResponse> events,Instant createdAt,Instant updatedAt,
            String termsHash,Integer acceptedQuoteVersion,int dayCountBasis,String uti,String venue,boolean collateralReuseConsent,
            Instant openCashDeclaredAt,Instant openCollateralDeclaredAt,Instant closeCashDeclaredAt,Instant closeCollateralDeclaredAt,
            String marginValuationReference,BigDecimal marginValuationAmount,Integer marginHaircutBps,Instant marginDeliveredAt,
            Instant defaultNoticeAt,DefaultGround defaultNoticeGround,DefaultGround defaultGround,UUID defaultingPartyEntityId,
            String disputeReason,TradeStatus preDisputeStatus,Instant disputedAt,List<SubstitutionResponse> substitutions){}
    public record SubstitutionResponse(UUID id,UUID assetId,BigDecimal quantity,SubstitutionStatus status,UUID requestedBy,
            Instant requestedAt,Instant decidedAt,Instant replacementReceivedAt,Instant originalReturnedAt,Instant completedAt,String note){}
    public record EventResponse(UUID id,LifecycleEventType type,UUID actorEntityId,String actorName,
            BigDecimal amount,UUID assetId,BigDecimal quantity,String reference,String note,Instant createdAt){}
}

