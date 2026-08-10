package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.asset.api.*;
import de.makibytes.registerwerk.customer.api.*;
import de.makibytes.registerwerk.repo.api.*;
import de.makibytes.registerwerk.repo.api.RepoTypes.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class RepoTradeService {
    public enum SettlementLeg { CASH, COLLATERAL }

    private final RepoDeskProperties properties;
    private final RepoTradeRepository trades;
    private final RepoLifecycleEventRepository events;
    private final LegalEntityRepository entities;
    private final AssetRepository assets;

    public RepoTradeService(RepoDeskProperties properties, RepoTradeRepository trades,
                            RepoLifecycleEventRepository events, LegalEntityRepository entities,
                            AssetRepository assets) {
        this.properties = properties; this.trades = trades; this.events = events;
        this.entities = entities; this.assets = assets;
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
        return view(trade, entityId);
    }

    @Transactional
    public TradeView issueMarginCall(UUID tradeId, UUID entityId, UUID userId,
                                     BigDecimal amount, Instant dueAt, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        requireEntityRole(entityId, trade.getCashLenderEntityId(), "cash lender");
        if (trade.getStatus() != TradeStatus.OPEN) throw new IllegalStateException("Trade is not open");
        requirePositive(amount, "Margin amount");
        if (dueAt == null || !dueAt.isAfter(Instant.now())) throw new IllegalArgumentException("Margin deadline must be in the future");
        trade.setMarginCallAmount(amount); trade.setMarginCallDueAt(dueAt); trade.setStatus(TradeStatus.MARGIN_CALL);
        record(trade, LifecycleEventType.MARGIN_CALL, entityId, userId, amount, null, null, null, note);
        return view(trade, entityId);
    }

    @Transactional
    public TradeView satisfyMarginCall(UUID tradeId, UUID entityId, UUID userId,
                                       String reference, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        requireEntityRole(entityId, trade.getCashBorrowerEntityId(), "cash borrower");
        if (trade.getStatus() != TradeStatus.MARGIN_CALL) throw new IllegalStateException("No margin call is open");
        BigDecimal amount = trade.getMarginCallAmount();
        trade.setMarginCallAmount(null); trade.setMarginCallDueAt(null); trade.setStatus(TradeStatus.OPEN);
        record(trade, LifecycleEventType.MARGIN_SATISFIED, entityId, userId, amount, null, null, reference, note);
        return view(trade, entityId);
    }

    @Transactional
    public TradeView requestSubstitution(UUID tradeId, UUID entityId, UUID userId,
                                         UUID assetId, BigDecimal quantity, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        requireEntityRole(entityId, trade.getCashBorrowerEntityId(), "cash borrower");
        if (trade.getStatus() != TradeStatus.OPEN) throw new IllegalStateException("Trade is not open");
        requirePositive(quantity, "Replacement collateral quantity");
        Asset replacement = assets.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("Replacement collateral asset not found"));
        if (replacement.getStatus() != AssetStatus.ISSUED) throw new IllegalStateException("Replacement collateral is not issued");
        if (assetId.equals(trade.getCollateralAssetId())) throw new IllegalArgumentException("Replacement must be a different asset");
        trade.setPendingSubstitutionAssetId(assetId); trade.setPendingSubstitutionQuantity(quantity);
        trade.setSubstitutionRequestedBy(entityId);
        record(trade, LifecycleEventType.SUBSTITUTION_REQUESTED, entityId, userId, null, assetId, quantity, null, note);
        return view(trade, entityId);
    }

    @Transactional
    public TradeView decideSubstitution(UUID tradeId, UUID entityId, UUID userId, boolean approve, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        requireEntityRole(entityId, trade.getCashLenderEntityId(), "cash lender");
        if (trade.getPendingSubstitutionAssetId() == null) throw new IllegalStateException("No substitution is pending");
        UUID replacement = trade.getPendingSubstitutionAssetId();
        BigDecimal quantity = trade.getPendingSubstitutionQuantity();
        if (approve) {
            trade.setCollateralAssetId(replacement); trade.setCollateralQuantity(quantity);
        }
        trade.setPendingSubstitutionAssetId(null); trade.setPendingSubstitutionQuantity(null);
        trade.setSubstitutionRequestedBy(null);
        record(trade, approve ? LifecycleEventType.SUBSTITUTION_APPROVED : LifecycleEventType.SUBSTITUTION_REJECTED,
                entityId, userId, null, replacement, quantity, null, note);
        return view(trade, entityId);
    }

    @Transactional
    public TradeView initiateClose(UUID tradeId, UUID entityId, UUID userId) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        if (trade.getStatus() != TradeStatus.OPEN) throw new IllegalStateException("Trade is not open");
        if (LocalDate.now(ZoneOffset.UTC).isBefore(trade.getEndDate())) {
            throw new IllegalStateException("Early termination requires a separately agreed amendment");
        }
        trade.setStatus(TradeStatus.PENDING_CLOSE);
        record(trade, LifecycleEventType.CLOSE_INITIATED, entityId, userId, null, null, null, null, "Closing settlement started");
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
            record(trade, LifecycleEventType.CLOSED, entityId, userId, null, null, null, null, "Both closing legs confirmed");
        }
        return view(trade, entityId);
    }

    @Transactional
    public TradeView declareDefault(UUID tradeId, UUID entityId, UUID userId, String note) {
        properties.requireReleased();
        RepoTrade trade = lockedPartyTrade(tradeId, entityId);
        requireEntityRole(entityId, trade.getCashLenderEntityId(), "cash lender");
        boolean overdueMargin = trade.getStatus() == TradeStatus.MARGIN_CALL
                && trade.getMarginCallDueAt() != null && !trade.getMarginCallDueAt().isAfter(Instant.now());
        boolean overdueClose = trade.getStatus() == TradeStatus.PENDING_CLOSE
                && LocalDate.now(ZoneOffset.UTC).isAfter(trade.getEndDate());
        if (!overdueMargin && !overdueClose) throw new IllegalStateException("No overdue obligation permits default declaration");
        trade.setStatus(TradeStatus.DEFAULTED);
        record(trade, LifecycleEventType.DEFAULT_DECLARED, entityId, userId, trade.getMarginCallAmount(),
                trade.getCollateralAssetId(), trade.getCollateralQuantity(), null, note);
        return view(trade, entityId);
    }

    private TradeView view(RepoTrade trade, UUID viewer) {
        String borrower = entityName(trade.getCashBorrowerEntityId());
        String lender = entityName(trade.getCashLenderEntityId());
        Asset asset = assets.findById(trade.getCollateralAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Repo collateral asset not found"));
        List<EventView> history = events.findByRepoTradeIdOrderByCreatedAtAsc(trade.getId()).stream()
                .map(event -> new EventView(event, entityName(event.getActorEntityId()))).toList();
        return new TradeView(trade, borrower, lender, asset.getName(), asset.getIsin(),
                trade.getCashBorrowerEntityId().equals(viewer), history);
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
        if (!expected.equals(actual)) throw new AccessDeniedException("Only the " + role + " may confirm this action");
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
        event.setReference(trim(reference)); event.setNote(trim(note)); events.save(event);
    }
    private String trim(String value){return value == null || value.isBlank() ? null : value.trim();}

    public record TradeView(RepoTrade trade, String borrowerName, String lenderName,
                            String collateralName, String collateralIsin, boolean borrower,
                            List<EventView> events) {}
    public record EventView(RepoLifecycleEvent event, String actorName) {}
}
