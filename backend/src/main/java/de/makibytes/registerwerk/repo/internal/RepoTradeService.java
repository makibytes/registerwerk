package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.asset.api.*;
import de.makibytes.registerwerk.customer.api.*;
import de.makibytes.registerwerk.repo.api.*;
import de.makibytes.registerwerk.repo.api.RepoTypes.*;
import de.makibytes.registerwerk.repo.events.RepoAuditEvent;
import de.makibytes.registerwerk.repo.events.RepoPartyNoticeEvent;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/**
 * Bilateral repo lifecycle (Phase 5, 5A-07/08/09, 5B-06). The platform records evidence and enforces
 * notice / cure / grace; it does not decide the merits of a dispute and computes no close-out (T5-09).
 * Every leg has a payer (who declares "sent") and a receiver (who confirms or disputes); a default can
 * only be grounded on an obligation that is unmet AND not covered by an unrebutted payer declaration.
 */
@Service
public class RepoTradeService {
    private static final Logger log = LoggerFactory.getLogger(RepoTradeService.class);

    public enum SettlementLeg { CASH, COLLATERAL }
    public enum Phase { OPEN, CLOSE }
    public enum SubstitutionLeg { REPLACEMENT_IN, ORIGINAL_OUT }

    private static final List<TradeStatus> DISPUTABLE = List.of(TradeStatus.PENDING_OPEN_SETTLEMENT,
            TradeStatus.OPEN, TradeStatus.MARGIN_CALL, TradeStatus.PENDING_CLOSE);
    private static final List<SubstitutionStatus> LIVE_SUBSTITUTION = List.of(SubstitutionStatus.PENDING, SubstitutionStatus.APPROVED);

    private final RepoDeskProperties properties;
    private final RepoTradeRepository trades;
    private final RepoLifecycleEventRepository events;
    private final LegalEntityRepository entities;
    private final AssetRepository assets;
    private final RepoSubstitutionRequestRepository substitutions;
    private final RepoControls controls;
    private final ApplicationEventPublisher publisher;

    public RepoTradeService(RepoDeskProperties properties, RepoTradeRepository trades,
                            RepoLifecycleEventRepository events, LegalEntityRepository entities,
                            AssetRepository assets, RepoSubstitutionRequestRepository substitutions,
                            RepoControls controls, ApplicationEventPublisher publisher) {
        this.properties = properties; this.trades = trades; this.events = events;
        this.entities = entities; this.assets = assets; this.substitutions = substitutions;
        this.controls = controls; this.publisher = publisher;
    }

    @Transactional(readOnly = true)
    public List<TradeView> list(UUID entityId) {
        properties.requireReleased(); requireEntity(entityId);
        return trades.findByParty(entityId).stream().map(trade -> view(trade, entityId)).toList();
    }

    @Transactional(readOnly = true)
    public TradeView get(UUID tradeId, UUID entityId) {
        properties.requireReleased();
        RepoTrade trade = requireTrade(tradeId); requireParty(trade, entityId);
        return view(trade, entityId);
    }

    /** The Art. 4 SFTR fields the platform holds, for the parties' own reporting (no reporting is done here). */
    @Transactional(readOnly = true)
    public SftrFields sftrFields(UUID tradeId, UUID entityId) {
        properties.requireReleased();
        RepoTrade trade = requireTrade(tradeId); requireParty(trade, entityId);
        Asset asset = requireAsset(trade.getCollateralAssetId());
        LegalEntity lender = entities.findById(trade.getCashLenderEntityId()).orElseThrow();
        LegalEntity borrower = entities.findById(trade.getCashBorrowerEntityId()).orElseThrow();
        return new SftrFields(trade.getUti(), trade.getVenue(), lender.getLeiCode(), borrower.getLeiCode(),
                "CASH_LENDER_IS_BUYER_SIDE", trade.getCashCurrency(), trade.getCashAmount(), trade.getRepoRate(),
                trade.getDayCountBasis(), trade.getStartDate(), trade.getEndDate(), trade.getRepurchaseAmount(),
                asset.getIsin(), trade.getCollateralQuantity(), trade.getHaircutBps(), trade.isCollateralReuseConsent(),
                trade.getTermsHash(), trade.getStatus(),
                "Record-keeping aid only. Registerwerk does not report under SFTR; each party remains responsible for its own reporting.");
    }

    // ── opening ─────────────────────────────────────────────────────────────

    /** Payer declares the leg as sent (open cash: lender; open collateral: borrower; close cash: borrower; close collateral: lender). */
    @Transactional
    public TradeView declareLegSent(UUID tradeId, UUID entityId, UUID userId, Phase phase, SettlementLeg leg, String reference) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        TradeStatus required = phase == Phase.OPEN ? TradeStatus.PENDING_OPEN_SETTLEMENT : TradeStatus.PENDING_CLOSE;
        if (trade.getStatus() != required) throw new IllegalStateException("The " + phase.name().toLowerCase() + " settlement is not pending");
        boolean cash = leg == SettlementLeg.CASH;
        UUID payer = phase == Phase.OPEN == cash ? trade.getCashLenderEntityId() : trade.getCashBorrowerEntityId();
        requireEntityRole(entityId, payer, "payer of this leg");
        Instant declaredAt = declaredAt(trade, phase, leg);
        if (declaredAt == null) {
            Instant now = Instant.now();
            if (phase == Phase.OPEN) { if (cash) trade.setOpenCashDeclaredAt(now); else trade.setOpenCollateralDeclaredAt(now); }
            else { if (cash) trade.setCloseCashDeclaredAt(now); else trade.setCloseCollateralDeclaredAt(now); }
            record(trade, cash ? LifecycleEventType.CASH_SENT : LifecycleEventType.COLLATERAL_SENT, entityId, userId,
                    cash ? (phase == Phase.OPEN ? trade.getCashAmount() : trade.getRepurchaseAmount()) : null,
                    cash ? null : trade.getCollateralAssetId(), cash ? null : trade.getCollateralQuantity(),
                    requireReference(reference), phase + " leg declared sent by the payer");
            audit(trade, "LEG_DECLARED", userId, Map.of("phase", phase.name(), "leg", leg.name()));
        }
        return view(trade, entityId);
    }

    @Transactional
    public TradeView confirmOpenLeg(UUID tradeId, UUID entityId, UUID userId,
                                    SettlementLeg leg, String reference) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        if (trade.getStatus() != TradeStatus.PENDING_OPEN_SETTLEMENT) {
            if (trade.getStatus() == TradeStatus.OPEN
                    && ((leg == SettlementLeg.CASH && trade.isOpenCashConfirmed())
                    || (leg == SettlementLeg.COLLATERAL && trade.isOpenCollateralConfirmed()))) {
                return view(trade, entityId);
            }
            throw new IllegalStateException("Opening settlement is not pending");
        }
        if (LocalDate.now(ZoneOffset.UTC).isBefore(trade.getStartDate())) {
            throw new IllegalStateException("Opening settlement cannot be confirmed before the start date");
        }
        if (leg == SettlementLeg.CASH) {
            requireEntityRole(entityId, trade.getCashBorrowerEntityId(), "cash borrower");
            if (!trade.isOpenCashConfirmed()) {
                trade.setOpenCashConfirmed(true);
                record(trade, LifecycleEventType.OPEN_CASH_CONFIRMED, entityId, userId,
                        trade.getCashAmount(), null, null, reference, "Opening cash received");
            }
        } else {
            requireEntityRole(entityId, trade.getCashLenderEntityId(), "cash lender");
            if (!trade.isOpenCollateralConfirmed()) {
                trade.setOpenCollateralConfirmed(true);
                record(trade, LifecycleEventType.OPEN_COLLATERAL_CONFIRMED, entityId, userId,
                        null, trade.getCollateralAssetId(), trade.getCollateralQuantity(), reference,
                        "Opening collateral received");
            }
        }
        if (trade.isOpenCashConfirmed() && trade.isOpenCollateralConfirmed()) {
            trade.setStatus(TradeStatus.OPEN);
            record(trade, LifecycleEventType.OPEN_SETTLED, entityId, userId, null, null, null,
                    null, "Both opening legs confirmed");
        }
        flagIneligibleParties(trade, userId);
        audit(trade, "OPEN_LEG_CONFIRMED", userId, Map.of("leg", leg.name()));
        return view(trade, entityId);
    }

    // ── margin ──────────────────────────────────────────────────────────────

    /**
     * Lender's margin call. It carries a valuation snapshot (reference + amount at the agreed haircut) and the
     * amount may not exceed the shortfall that snapshot implies; the borrower gets at least the configured cure period.
     */
    @Transactional
    public TradeView issueMarginCall(UUID tradeId, UUID entityId, UUID userId, BigDecimal amount, Instant dueAt,
                                     String valuationReference, BigDecimal valuationAmount, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        requireEntityRole(entityId, trade.getCashLenderEntityId(), "cash lender");
        if (trade.getStatus() != TradeStatus.OPEN) throw new IllegalStateException("Trade is not open");
        requireNoLiveSubstitution(trade, "a margin call");
        requireActorEligible(entityId, "a margin call");
        requirePositive(amount, "Margin amount");
        requirePositive(valuationAmount, "Valuation amount");
        if (valuationReference == null || valuationReference.isBlank()) throw new IllegalArgumentException("A valuation reference is required");
        Instant earliest = Instant.now().plus(Duration.ofHours(properties.getMinMarginCureHours()));
        if (dueAt == null || dueAt.isBefore(earliest)) {
            throw new IllegalArgumentException("Margin deadline must allow at least " + properties.getMinMarginCureHours() + " hours to cure");
        }
        BigDecimal adjusted = valuationAmount.multiply(BigDecimal.valueOf(10_000L - trade.getHaircutBps()))
                .divide(BigDecimal.valueOf(10_000L), 18, RoundingMode.HALF_UP);
        BigDecimal shortfall = CurrencyRules.round(trade.getCashCurrency(), trade.getRepurchaseAmount().subtract(adjusted));
        if (shortfall.signum() <= 0) throw new IllegalArgumentException("The valuation shows no shortfall: collateral after haircut covers the repurchase amount");
        if (amount.compareTo(shortfall) > 0) {
            throw new IllegalArgumentException("Margin amount exceeds the shortfall of " + shortfall.toPlainString()
                    + " implied by the valuation");
        }
        CurrencyRules.requireMinorUnitScale(trade.getCashCurrency(), amount, "Margin amount");
        expirePendingSubstitutions(trade);
        trade.setMarginCallAmount(amount); trade.setMarginCallDueAt(dueAt); trade.setStatus(TradeStatus.MARGIN_CALL);
        trade.setMarginValuationReference(valuationReference.trim()); trade.setMarginValuationAmount(valuationAmount);
        trade.setMarginHaircutBps(trade.getHaircutBps());
        trade.setMarginDeliveredAt(null); trade.setMarginDeliveredReference(null);
        record(trade, LifecycleEventType.MARGIN_CALL, entityId, userId, amount, null, null, valuationReference, note);
        audit(trade, "MARGIN_CALL_ISSUED", userId, Map.of("amount", amount.toPlainString(), "dueAt", dueAt.toString()));
        notifyBoth(trade, "Registerwerk: margin call on repo", "A margin call of " + amount.toPlainString() + " "
                + trade.getCashCurrency() + " is due by " + dueAt + ".");
        return view(trade, entityId);
    }

    /** Borrower states that the top-up was sent. This does NOT clear the call - the lender confirms receipt. */
    @Transactional
    public TradeView declareMarginDelivered(UUID tradeId, UUID entityId, UUID userId, String reference, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        requireEntityRole(entityId, trade.getCashBorrowerEntityId(), "cash borrower");
        if (trade.getStatus() != TradeStatus.MARGIN_CALL) throw new IllegalStateException("No margin call is open");
        if (trade.getMarginDeliveredAt() != null) return view(trade, entityId);
        Instant now = Instant.now();
        trade.setMarginDeliveredAt(now);
        trade.setMarginDeliveredReference(requireReference(reference));
        boolean late = trade.getMarginCallDueAt() != null && now.isAfter(trade.getMarginCallDueAt());
        record(trade, LifecycleEventType.MARGIN_DELIVERED, entityId, userId, trade.getMarginCallAmount(), null, null,
                reference, (late ? "LATE: declared after the deadline. " : "") + (note == null ? "" : note));
        audit(trade, "MARGIN_DELIVERED_DECLARED", userId, Map.of("late", late));
        return view(trade, entityId);
    }

    /** Lender confirms the top-up: only this clears the margin call. */
    @Transactional
    public TradeView confirmMarginReceived(UUID tradeId, UUID entityId, UUID userId, String reference, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        requireEntityRole(entityId, trade.getCashLenderEntityId(), "cash lender");
        if (trade.getStatus() != TradeStatus.MARGIN_CALL) throw new IllegalStateException("No margin call is open");
        BigDecimal amount = trade.getMarginCallAmount();
        clearMargin(trade);
        trade.setStatus(TradeStatus.OPEN);
        record(trade, LifecycleEventType.MARGIN_SATISFIED, entityId, userId, amount, null, null, reference, note);
        audit(trade, "MARGIN_CONFIRMED", userId, Map.of());
        return view(trade, entityId);
    }

    // ── substitution ────────────────────────────────────────────────────────

    @Transactional
    public TradeView requestSubstitution(UUID tradeId, UUID entityId, UUID userId,
                                         UUID assetId, BigDecimal quantity, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        requireEntityRole(entityId, trade.getCashBorrowerEntityId(), "cash borrower");
        if (trade.getStatus() != TradeStatus.OPEN) throw new IllegalStateException("Trade is not open");
        requireNoLiveSubstitution(trade, "a new substitution request");
        requirePositive(quantity, "Replacement collateral quantity");
        requireActorEligible(entityId, "a collateral substitution");
        Asset replacement = assets.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("Replacement collateral asset not found"));
        if (replacement.getStatus() != AssetStatus.ISSUED) throw new IllegalStateException("Replacement collateral is not issued");
        if (assetId.equals(trade.getCollateralAssetId())) throw new IllegalArgumentException("Replacement must be a different asset");
        controls.requireTermWithinCollateralLife(assetId, trade.getEndDate());
        controls.requireHolding(entityId, assetId, quantity);
        RepoSubstitutionRequest request = new RepoSubstitutionRequest();
        request.setRepoTradeId(trade.getId()); request.setAssetId(assetId); request.setQuantity(quantity);
        request.setRequestedBy(entityId); request.setNote(trim(note));
        substitutions.save(request);
        record(trade, LifecycleEventType.SUBSTITUTION_REQUESTED, entityId, userId, null, assetId, quantity, null, note);
        audit(trade, "SUBSTITUTION_REQUESTED", userId, Map.of("assetId", assetId.toString()));
        return view(trade, entityId);
    }

    /** Lender decision on a pending request; approval starts the two-leg settlement, it does not swap yet. */
    @Transactional
    public TradeView decideSubstitution(UUID tradeId, UUID requestId, UUID entityId, UUID userId, boolean approve, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        requireEntityRole(entityId, trade.getCashLenderEntityId(), "cash lender");
        if (trade.getStatus() != TradeStatus.OPEN) throw new IllegalStateException("Trade is not open");
        RepoSubstitutionRequest request = substitutions.findById(requestId)
                .filter(r -> r.getRepoTradeId().equals(tradeId))
                .orElseThrow(() -> new EntityNotFoundException("Substitution request not found"));
        if (request.getStatus() != SubstitutionStatus.PENDING) throw new IllegalStateException("Substitution request is no longer pending");
        if (approve) {
            requireActorEligible(entityId, "a collateral substitution");
            controls.requireTermWithinCollateralLife(request.getAssetId(), trade.getEndDate());
            controls.requireHolding(trade.getCashBorrowerEntityId(), request.getAssetId(), request.getQuantity());
        }
        request.setStatus(approve ? SubstitutionStatus.APPROVED : SubstitutionStatus.REJECTED);
        request.setDecidedBy(entityId); request.setDecidedAt(Instant.now());
        substitutions.saveAndFlush(request);
        record(trade, approve ? LifecycleEventType.SUBSTITUTION_APPROVED : LifecycleEventType.SUBSTITUTION_REJECTED,
                entityId, userId, null, request.getAssetId(), request.getQuantity(), null, note);
        audit(trade, approve ? "SUBSTITUTION_APPROVED" : "SUBSTITUTION_REJECTED", userId, Map.of("requestId", requestId.toString()));
        return view(trade, entityId);
    }

    /** Requester withdraws a pending request; either party cancels an approved one before any leg was confirmed. */
    @Transactional
    public TradeView withdrawSubstitution(UUID tradeId, UUID entityId, UUID userId, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        RepoSubstitutionRequest request = substitutions.findFirstByRepoTradeIdAndStatusIn(tradeId, LIVE_SUBSTITUTION)
                .orElseThrow(() -> new IllegalStateException("No substitution is pending"));
        if (request.getStatus() == SubstitutionStatus.PENDING) {
            requireEntityRole(entityId, request.getRequestedBy(), "requester");
        } else if (request.getReplacementReceivedAt() != null || request.getOriginalReturnedAt() != null) {
            throw new IllegalStateException("A leg of the substitution is already confirmed; raise a dispute instead");
        }
        request.setStatus(SubstitutionStatus.WITHDRAWN);
        substitutions.saveAndFlush(request);
        record(trade, LifecycleEventType.SUBSTITUTION_WITHDRAWN, entityId, userId, null, request.getAssetId(), request.getQuantity(), null, note);
        audit(trade, "SUBSTITUTION_WITHDRAWN", userId, Map.of());
        return view(trade, entityId);
    }

    /** Lender confirms receipt of the replacement; borrower confirms return of the original. Both -> swap. */
    @Transactional
    public TradeView confirmSubstitutionLeg(UUID tradeId, UUID entityId, UUID userId, SubstitutionLeg leg, String reference) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        if (trade.getStatus() != TradeStatus.OPEN) throw new IllegalStateException("Trade is not open");
        RepoSubstitutionRequest request = substitutions.findFirstByRepoTradeIdAndStatusIn(tradeId, List.of(SubstitutionStatus.APPROVED))
                .orElseThrow(() -> new IllegalStateException("No approved substitution awaits settlement"));
        if (leg == SubstitutionLeg.REPLACEMENT_IN) {
            requireEntityRole(entityId, trade.getCashLenderEntityId(), "cash lender");
            if (request.getReplacementReceivedAt() == null) {
                request.setReplacementReceivedAt(Instant.now());
                record(trade, LifecycleEventType.SUBSTITUTION_REPLACEMENT_RECEIVED, entityId, userId, null,
                        request.getAssetId(), request.getQuantity(), reference, "Replacement collateral received");
            }
        } else {
            requireEntityRole(entityId, trade.getCashBorrowerEntityId(), "cash borrower");
            if (request.getOriginalReturnedAt() == null) {
                request.setOriginalReturnedAt(Instant.now());
                record(trade, LifecycleEventType.SUBSTITUTION_ORIGINAL_RETURNED, entityId, userId, null,
                        trade.getCollateralAssetId(), trade.getCollateralQuantity(), reference, "Original collateral returned");
            }
        }
        if (request.getReplacementReceivedAt() != null && request.getOriginalReturnedAt() != null) {
            UUID originalAsset = trade.getCollateralAssetId();
            trade.setCollateralAssetId(request.getAssetId());
            trade.setCollateralQuantity(request.getQuantity());
            request.setStatus(SubstitutionStatus.COMPLETED);
            request.setCompletedAt(Instant.now());
            record(trade, LifecycleEventType.SUBSTITUTION_COMPLETED, entityId, userId, null, request.getAssetId(),
                    request.getQuantity(), null, "Collateral swapped (was asset " + originalAsset + ")");
            audit(trade, "SUBSTITUTION_COMPLETED", userId, Map.of("requestId", String.valueOf(request.getId())));
        } else {
            audit(trade, "SUBSTITUTION_LEG_CONFIRMED", userId, Map.of("leg", leg.name()));
        }
        substitutions.save(request);
        return view(trade, entityId);
    }

    // ── closing ─────────────────────────────────────────────────────────────

    @Transactional
    public TradeView initiateClose(UUID tradeId, UUID entityId, UUID userId) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        if (trade.getStatus() != TradeStatus.OPEN) throw new IllegalStateException("Trade is not open");
        if (LocalDate.now(ZoneOffset.UTC).isBefore(trade.getEndDate())) {
            throw new IllegalStateException("Early termination requires a separately agreed amendment");
        }
        requireNoLiveSubstitution(trade, "closing");
        expirePendingSubstitutions(trade);
        trade.setStatus(TradeStatus.PENDING_CLOSE);
        record(trade, LifecycleEventType.CLOSE_INITIATED, entityId, userId, null, null, null, null, "Closing settlement started");
        audit(trade, "CLOSE_INITIATED", userId, Map.of());
        return view(trade, entityId);
    }

    @Transactional
    public TradeView confirmCloseLeg(UUID tradeId, UUID entityId, UUID userId,
                                     SettlementLeg leg, String reference) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        if (trade.getStatus() != TradeStatus.PENDING_CLOSE) {
            if (trade.getStatus() == TradeStatus.CLOSED
                    && ((leg == SettlementLeg.CASH && trade.isCloseCashConfirmed())
                    || (leg == SettlementLeg.COLLATERAL && trade.isCloseCollateralConfirmed()))) {
                return view(trade, entityId);
            }
            throw new IllegalStateException("Closing settlement is not pending");
        }
        if (leg == SettlementLeg.CASH) {
            requireEntityRole(entityId, trade.getCashLenderEntityId(), "cash lender");
            if (!trade.isCloseCashConfirmed()) {
                trade.setCloseCashConfirmed(true);
                record(trade, LifecycleEventType.CLOSE_CASH_CONFIRMED, entityId, userId,
                        trade.getRepurchaseAmount(), null, null, reference, "Repurchase cash received");
            }
        } else {
            requireEntityRole(entityId, trade.getCashBorrowerEntityId(), "cash borrower");
            if (!trade.isCloseCollateralConfirmed()) {
                trade.setCloseCollateralConfirmed(true);
                record(trade, LifecycleEventType.CLOSE_COLLATERAL_CONFIRMED, entityId, userId,
                        null, trade.getCollateralAssetId(), trade.getCollateralQuantity(), reference,
                        "Collateral returned");
            }
        }
        if (trade.isCloseCashConfirmed() && trade.isCloseCollateralConfirmed()) {
            trade.setStatus(TradeStatus.CLOSED);
            trade.setDefaultNoticeAt(null); trade.setDefaultNoticeBy(null); trade.setDefaultNoticeGround(null);
            record(trade, LifecycleEventType.CLOSED, entityId, userId, null, null, null, null, "Both closing legs confirmed");
        }
        flagIneligibleParties(trade, userId);
        audit(trade, "CLOSE_LEG_CONFIRMED", userId, Map.of("leg", leg.name()));
        return view(trade, entityId);
    }

    // ── default: notice, grace, declaration ─────────────────────────────────

    /** Step 1: the creditor serves a default notice once the obligation is overdue; starts the grace timer. */
    @Transactional
    public TradeView serveDefaultNotice(UUID tradeId, UUID entityId, UUID userId, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        DefaultGround ground = overdueGround(trade).orElseThrow(() ->
                new IllegalStateException("No overdue obligation permits a default notice"));
        requireEntityRole(entityId, creditor(trade, ground), "creditor of the overdue obligation");
        requireActorEligible(entityId, "a default notice");
        requireNotCoveredByDeclaration(trade, ground);
        if (trade.getDefaultNoticeAt() != null && ground == trade.getDefaultNoticeGround()) {
            throw new IllegalStateException("A default notice for this obligation was already served");
        }
        trade.setDefaultNoticeAt(Instant.now()); trade.setDefaultNoticeBy(entityId); trade.setDefaultNoticeGround(ground);
        record(trade, LifecycleEventType.DEFAULT_NOTICE, entityId, userId, null, null, null, ground.name(), note);
        audit(trade, "DEFAULT_NOTICE_SERVED", userId, Map.of("ground", ground.name()));
        notifyBoth(trade, "Registerwerk: default notice on repo", "A default notice was served (" + ground
                + "). A default can be declared after " + properties.getDefaultGraceHours()
                + " hours if the obligation is still unmet.");
        return view(trade, entityId);
    }

    /** Step 2: after the grace period, only while the obligation is still unmet and no payer declaration covers it. */
    @Transactional
    public TradeView declareDefault(UUID tradeId, UUID entityId, UUID userId, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        DefaultGround ground = overdueGround(trade).orElseThrow(() ->
                new IllegalStateException("No overdue obligation permits default declaration"));
        requireEntityRole(entityId, creditor(trade, ground), "creditor of the overdue obligation");
        requireActorEligible(entityId, "a default declaration");
        if (trade.getDefaultNoticeAt() == null || trade.getDefaultNoticeGround() != ground) {
            throw new IllegalStateException("A default notice must be served first");
        }
        Instant earliest = trade.getDefaultNoticeAt().plus(Duration.ofHours(properties.getDefaultGraceHours()));
        if (Instant.now().isBefore(earliest)) {
            throw new IllegalStateException("The grace period after the default notice runs until " + earliest);
        }
        requireNotCoveredByDeclaration(trade, ground);
        expirePendingSubstitutions(trade);
        trade.setStatus(TradeStatus.DEFAULTED);
        trade.setDefaultGround(ground);
        trade.setDefaultingPartyEntityId(debtor(trade, ground));
        record(trade, LifecycleEventType.DEFAULT_DECLARED, entityId, userId, trade.getMarginCallAmount(),
                trade.getCollateralAssetId(), trade.getCollateralQuantity(), ground.name(), note);
        audit(trade, "DEFAULT_DECLARED", userId, Map.of("ground", ground.name(),
                "defaultingParty", trade.getDefaultingPartyEntityId().toString()));
        notifyBoth(trade, "Registerwerk: default declared on repo", "A default was declared (" + ground + ").");
        return view(trade, entityId);
    }

    // ── disputes ────────────────────────────────────────────────────────────

    /** Either party freezes the trade (no default, close, margin or substitution) and calls the operator queue. */
    @Transactional
    public TradeView openDispute(UUID tradeId, UUID entityId, UUID userId, String reason) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        if (!DISPUTABLE.contains(trade.getStatus())) throw new IllegalStateException("This trade cannot be disputed in its current state");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A dispute reason is required");
        expirePendingSubstitutions(trade);
        trade.setPreDisputeStatus(trade.getStatus());
        trade.setStatus(TradeStatus.DISPUTED);
        trade.setDisputeReason(reason.trim()); trade.setDisputedAt(Instant.now()); trade.setDisputedBy(entityId);
        trade.setDefaultNoticeAt(null); trade.setDefaultNoticeBy(null); trade.setDefaultNoticeGround(null);
        record(trade, LifecycleEventType.DISPUTE_OPENED, entityId, userId, null, null, null, null, reason);
        audit(trade, "DISPUTE_OPENED", userId, Map.of("from", trade.getPreDisputeStatus().name()));
        notifyBoth(trade, "Registerwerk: repo trade disputed", "The trade was put into dispute: " + reason.trim());
        return view(trade, entityId);
    }

    /** Evidence note from a party (any state). */
    @Transactional
    public TradeView addNote(UUID tradeId, UUID entityId, UUID userId, String text) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        if (text == null || text.isBlank()) throw new IllegalArgumentException("A note is required");
        record(trade, LifecycleEventType.EVIDENCE_NOTE, entityId, userId, null, null, null, null, text);
        audit(trade, "EVIDENCE_NOTE_ADDED", userId, Map.of());
        return view(trade, entityId);
    }

    /** Operator queue (read). */
    @Transactional(readOnly = true)
    public List<TradeView> disputes() {
        return trades.findByStatus(TradeStatus.DISPUTED).stream().map(t -> view(t, null)).toList();
    }

    /**
     * Operator records how a dispute ends (step-up + second approver at the web layer). The operator does not
     * decide the merits: RESUME returns the trade to where it was, CLOSE (only from PENDING_CLOSE) and CANCEL
     * (only from PENDING_OPEN_SETTLEMENT) record the parties' agreed outcome under the stated legal basis.
     */
    @Transactional
    public TradeView resolveDispute(UUID tradeId, UUID operatorId, DisputeResolution resolution, String legalBasis,
                                    String note, UUID approverId) {
        RepoTrade trade = trades.findByIdForUpdate(tradeId).orElseThrow(() -> new EntityNotFoundException("Repo trade not found"));
        if (trade.getStatus() != TradeStatus.DISPUTED) throw new IllegalStateException("Trade is not in dispute");
        if (legalBasis == null || legalBasis.isBlank()) throw new IllegalArgumentException("A legal basis is required");
        TradeStatus before = trade.getPreDisputeStatus();
        switch (resolution) {
            case RESUME -> trade.setStatus(before);
            case CLOSE -> {
                if (before != TradeStatus.PENDING_CLOSE) throw new IllegalArgumentException("Only a dispute raised during closing can be resolved as CLOSE");
                trade.setStatus(TradeStatus.CLOSED);
            }
            case CANCEL -> {
                if (before != TradeStatus.PENDING_OPEN_SETTLEMENT) throw new IllegalArgumentException("Only a dispute raised before opening can be resolved as CANCEL");
                trade.setStatus(TradeStatus.CANCELLED);
            }
        }
        trade.setPreDisputeStatus(null); trade.setDisputeReason(null);
        RepoLifecycleEvent e = new RepoLifecycleEvent();
        e.setRepoTradeId(trade.getId()); e.setEventType(LifecycleEventType.DISPUTE_RESOLVED);
        e.setActorUserId(operatorId); e.setReference(resolution.name());
        e.setNote(clip(trim("Operator record (" + legalBasis.trim() + ")" + (note == null || note.isBlank() ? "" : ": " + note.trim()))));
        events.save(e);
        publisher.publishEvent(new RepoAuditEvent("RepoTrade", trade.getId(), "DISPUTE_RESOLVED", operatorId, "REGISTRY_ADMIN",
                Map.of("resolution", resolution.name(), "legalBasis", legalBasis.trim(), "resumedTo", trade.getStatus().name()),
                approverId));
        notifyBoth(trade, "Registerwerk: repo dispute recorded as resolved", "The operator recorded the dispute as " + resolution + ".");
        log.warn("Repo dispute resolved: trade={} resolution={} by={}", tradeId, resolution, operatorId);
        return view(trade, null);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    Optional<DefaultGround> overdueGround(RepoTrade trade) {
        if (trade.getStatus() == TradeStatus.MARGIN_CALL && trade.getMarginCallDueAt() != null
                && !trade.getMarginCallDueAt().isAfter(Instant.now())) {
            return Optional.of(DefaultGround.MARGIN_NOT_MET);
        }
        if (trade.getStatus() == TradeStatus.PENDING_CLOSE && LocalDate.now(ZoneOffset.UTC).isAfter(trade.getEndDate())) {
            if (!trade.isCloseCashConfirmed()) return Optional.of(DefaultGround.REPURCHASE_UNPAID);
            if (!trade.isCloseCollateralConfirmed()) return Optional.of(DefaultGround.COLLATERAL_RETURN_FAILURE);
        }
        return Optional.empty();
    }

    private void requireNotCoveredByDeclaration(RepoTrade trade, DefaultGround ground) {
        boolean covered = switch (ground) {
            case MARGIN_NOT_MET -> trade.getMarginDeliveredAt() != null && trade.getMarginCallDueAt() != null
                    && !trade.getMarginDeliveredAt().isAfter(trade.getMarginCallDueAt());
            case REPURCHASE_UNPAID -> trade.getCloseCashDeclaredAt() != null;
            case COLLATERAL_RETURN_FAILURE -> trade.getCloseCollateralDeclaredAt() != null;
        };
        if (covered) {
            throw new IllegalStateException("The counterparty declared performance of this obligation; confirm receipt "
                    + "or raise a dispute - a default cannot rest on an unrebutted declaration");
        }
    }

    private UUID creditor(RepoTrade trade, DefaultGround ground) {
        return ground == DefaultGround.COLLATERAL_RETURN_FAILURE ? trade.getCashBorrowerEntityId() : trade.getCashLenderEntityId();
    }

    private UUID debtor(RepoTrade trade, DefaultGround ground) {
        return ground == DefaultGround.COLLATERAL_RETURN_FAILURE ? trade.getCashLenderEntityId() : trade.getCashBorrowerEntityId();
    }

    private Instant declaredAt(RepoTrade t, Phase phase, SettlementLeg leg) {
        return phase == Phase.OPEN
                ? (leg == SettlementLeg.CASH ? t.getOpenCashDeclaredAt() : t.getOpenCollateralDeclaredAt())
                : (leg == SettlementLeg.CASH ? t.getCloseCashDeclaredAt() : t.getCloseCollateralDeclaredAt());
    }

    private void clearMargin(RepoTrade trade) {
        trade.setMarginCallAmount(null); trade.setMarginCallDueAt(null);
        trade.setMarginValuationReference(null); trade.setMarginValuationAmount(null); trade.setMarginHaircutBps(null);
        trade.setMarginDeliveredAt(null); trade.setMarginDeliveredReference(null);
    }

    private void requireNoLiveSubstitution(RepoTrade trade, String action) {
        if (substitutions.findFirstByRepoTradeIdAndStatusIn(trade.getId(), List.of(SubstitutionStatus.APPROVED)).isPresent()
                || (action.startsWith("a new") && substitutions.findFirstByRepoTradeIdAndStatusIn(trade.getId(), LIVE_SUBSTITUTION).isPresent())) {
            throw new IllegalStateException("A collateral substitution is in progress; settle or withdraw it before " + action);
        }
    }

    /** Any status change of the trade expires substitution requests that were only pending. */
    private void expirePendingSubstitutions(RepoTrade trade) {
        for (RepoSubstitutionRequest r : substitutions.findByRepoTradeIdAndStatusIn(trade.getId(), List.of(SubstitutionStatus.PENDING))) {
            r.setStatus(SubstitutionStatus.EXPIRED);
            substitutions.save(r);
            record(trade, LifecycleEventType.SUBSTITUTION_EXPIRED, null, null, null, r.getAssetId(), r.getQuantity(), null,
                    "Pending substitution expired because the trade changed state");
        }
    }

    private void requireActorEligible(UUID entityId, String purpose) {
        controls.requireEligible(entities.findById(entityId).orElseThrow(() -> new AccessDeniedException("Company context not found")), purpose);
    }

    /** Leg confirmations stay possible (an unwinding must remain possible) but flag a party that became ineligible. */
    private void flagIneligibleParties(RepoTrade trade, UUID userId) {
        for (UUID party : List.of(trade.getCashBorrowerEntityId(), trade.getCashLenderEntityId())) {
            List<String> reasons = controls.eligibilityReasons(party);
            if (reasons.isEmpty()) continue;
            String text = String.join("; ", reasons);
            String reference = party + ":" + Integer.toHexString(text.hashCode());
            if (events.existsByRepoTradeIdAndEventTypeAndReference(trade.getId(), LifecycleEventType.PARTY_FLAGGED, reference)) continue;
            record(trade, LifecycleEventType.PARTY_FLAGGED, null, userId, null, null, null, reference,
                    "A party to this trade is no longer eligible; the registry operator has been informed.");
            audit(trade, "PARTY_FLAGGED", userId, Map.of("partyId", party.toString(), "reasons", text));
            log.warn("Repo trade {} party {} flagged: {}", trade.getId(), party, text);
        }
    }

    private void notifyBoth(RepoTrade trade, String subject, String message) {
        publisher.publishEvent(new RepoPartyNoticeEvent(trade.getId(),
                List.of(trade.getCashBorrowerEntityId(), trade.getCashLenderEntityId()), subject, message));
    }

    private void audit(RepoTrade trade, String action, UUID userId, Map<String, Object> details) {
        publisher.publishEvent(RepoAuditEvent.of("RepoTrade", trade.getId(), action, userId, details));
    }

    private String requireReference(String reference) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("A payment or transfer reference is required");
        return reference.trim();
    }

    private TradeView view(RepoTrade trade, UUID viewer) {
        String borrower = entityName(trade.getCashBorrowerEntityId());
        String lender = entityName(trade.getCashLenderEntityId());
        Asset asset = requireAsset(trade.getCollateralAssetId());
        List<EventView> history = events.findByRepoTradeIdOrderByCreatedAtAsc(trade.getId()).stream()
                .map(event -> new EventView(event, event.getActorEntityId() == null ? "Registerwerk" : entityName(event.getActorEntityId()))).toList();
        List<RepoSubstitutionRequest> subs = substitutions.findByRepoTradeIdOrderByRequestedAtAsc(trade.getId());
        return new TradeView(trade, borrower, lender, asset.getName(), asset.getIsin(),
                viewer != null && trade.getCashBorrowerEntityId().equals(viewer), history, subs);
    }

    private Asset requireAsset(UUID id) {
        return assets.findById(id).orElseThrow(() -> new EntityNotFoundException("Repo collateral asset not found"));
    }

    private RepoTrade lockedPartyTrade(UUID id, UUID entityId) {
        RepoTrade trade = trades.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Repo trade not found"));
        requireParty(trade, entityId); return trade;
    }
    private RepoTrade requireTrade(UUID id) { return trades.findById(id).orElseThrow(() -> new EntityNotFoundException("Repo trade not found")); }
    private void requireParty(RepoTrade trade, UUID entityId) {
        requireEntity(entityId);
        if (!entityId.equals(trade.getCashBorrowerEntityId()) && !entityId.equals(trade.getCashLenderEntityId()))
            throw new AccessDeniedException("Trade is not visible to this company");
    }
    private void requireEntityRole(UUID actual, UUID expected, String role) {
        if (!expected.equals(actual)) throw new AccessDeniedException("Only the " + role + " may perform this action");
    }
    private void requireEntity(UUID id) { if (id == null || !entities.existsById(id)) throw new AccessDeniedException("An active company context is required"); }
    private void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(field + " must be greater than zero");
    }
    private String entityName(UUID id) { return entities.findById(id).map(LegalEntity::getCurrentName).orElse("Unknown company"); }
    private void record(RepoTrade trade, LifecycleEventType type, UUID entityId, UUID userId,
                        BigDecimal amount, UUID assetId, BigDecimal quantity, String reference, String note) {
        RepoLifecycleEvent event = new RepoLifecycleEvent(); event.setRepoTradeId(trade.getId());
        event.setEventType(type); event.setActorEntityId(entityId); event.setActorUserId(userId);
        event.setAmount(amount); event.setAssetId(assetId); event.setQuantity(quantity);
        event.setReference(trim(reference)); event.setNote(clip(trim(note))); events.save(event);
    }
    /** repo_lifecycle_event.note is VARCHAR(1000): cap instead of failing the transition with a 500. */
    static String clip(String value){return value == null || value.length() <= 1000 ? value : value.substring(0, 1000);}
    private String trim(String value){return value == null || value.isBlank() ? null : value.trim();}

    public record TradeView(RepoTrade trade, String borrowerName, String lenderName,
                            String collateralName, String collateralIsin, boolean borrower,
                            List<EventView> events, List<RepoSubstitutionRequest> substitutions) {}
    public record EventView(RepoLifecycleEvent event, String actorName) {}
    public record SftrFields(String uti, String venue, String lenderLei, String borrowerLei, String roles,
                             String currency, BigDecimal principalAmount, BigDecimal repoRatePercent, int dayCountBasis,
                             LocalDate startDate, LocalDate maturityDate, BigDecimal repurchaseAmount,
                             String collateralIsin, BigDecimal collateralQuantity, int haircutBps,
                             boolean collateralReuseConsent, String termsHash, TradeStatus status, String notice) {}
}
