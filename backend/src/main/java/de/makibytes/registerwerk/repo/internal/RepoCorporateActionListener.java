package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionAnnouncedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.repo.api.RepoLifecycleEvent;
import de.makibytes.registerwerk.repo.api.RepoLifecycleEventRepository;
import de.makibytes.registerwerk.repo.api.RepoTrade;
import de.makibytes.registerwerk.repo.api.RepoTradeRepository;
import de.makibytes.registerwerk.repo.api.RepoTypes;
import de.makibytes.registerwerk.repo.events.RepoPartyNoticeEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * T5-10 interim for manufactured payments: a corporate action on collateral of an open repo is
 * recorded on the trade as an informational lifecycle event and both parties are told. Amounts
 * are not computed or enforced - the manufactured payment is a contractual matter (GMRA).
 */
@Component
class RepoCorporateActionListener {
    private final RepoTradeRepository trades;
    private final RepoLifecycleEventRepository events;
    private final CorporateActionRepository actions;
    private final ApplicationEventPublisher publisher;

    RepoCorporateActionListener(RepoTradeRepository trades, RepoLifecycleEventRepository events,
                                CorporateActionRepository actions, ApplicationEventPublisher publisher) {
        this.trades = trades; this.events = events; this.actions = actions; this.publisher = publisher;
    }

    @ApplicationModuleListener
    void on(CorporateActionAnnouncedEvent event) {
        actions.findById(event.corporateActionId()).ifPresent(action -> recordOnOpenTrades(action));
    }

    @Transactional
    void recordOnOpenTrades(CorporateAction action) {
        for (RepoTrade trade : trades.findByCollateralAssetIdAndStatusIn(action.getAssetId(), RepoTypes.TradeStatus.OPEN_STATES)) {
            record(trade, action);
        }
    }

    /** At acceptance: actions announced before the trade existed whose record date falls in the term. */
    @Transactional
    public void recordExisting(RepoTrade trade) {
        for (CorporateAction action : actions.findByAssetIdAndStatusIn(trade.getCollateralAssetId(), java.util.EnumSet.of(
                CorporateAction.Status.ANNOUNCED, CorporateAction.Status.SNAPSHOT_BLOCKED,
                CorporateAction.Status.RECORD_DATE_SET, CorporateAction.Status.COMPUTED,
                CorporateAction.Status.AWAITING_SETTLEMENT))) {
            record(trade, action);
        }
    }

    /** Also used at acceptance for actions announced before the trade existed. */
    @Transactional
    public void record(RepoTrade trade, CorporateAction action) {
        if (action.getRecordDate() != null && (action.getRecordDate().isBefore(trade.getStartDate())
                || action.getRecordDate().isAfter(trade.getEndDate()))) {
            return;
        }
        String reference = action.getId().toString();
        if (events.existsByRepoTradeIdAndEventTypeAndReference(trade.getId(),
                RepoTypes.LifecycleEventType.CORPORATE_ACTION_DURING_TERM, reference)) {
            return;
        }
        RepoLifecycleEvent entry = new RepoLifecycleEvent();
        entry.setRepoTradeId(trade.getId());
        entry.setEventType(RepoTypes.LifecycleEventType.CORPORATE_ACTION_DURING_TERM);
        entry.setAssetId(trade.getCollateralAssetId());
        entry.setQuantity(trade.getCollateralQuantity());
        entry.setReference(reference);
        String note = action.getActionType() + " on the collateral: record date " + action.getRecordDate()
                + ", payment date " + action.getPaymentDate() + ", " + trade.getCollateralQuantity().stripTrailingZeros().toPlainString()
                + " units pledged. Informational only - any manufactured payment is for the parties to settle under their agreement.";
        entry.setNote(note);
        events.save(entry);
        publisher.publishEvent(new RepoPartyNoticeEvent(trade.getId(),
                List.of(trade.getCashBorrowerEntityId(), trade.getCashLenderEntityId()),
                "Registerwerk: corporate action on repo collateral", note));
    }
}
