package de.makibytes.registerwerk.registerstatement.internal;

import de.makibytes.registerwerk.registerstatement.api.StatementTrigger;
import de.makibytes.registerwerk.asset.events.HolderEnteredEvent;
import de.makibytes.registerwerk.asset.events.HolderRegisterChangedEvent;
import de.makibytes.registerwerk.indexer.events.HolderBalanceSyncedEvent;
import de.makibytes.registerwerk.shared.RegisterNotReconciledException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges register mutations to §19 statement issuance.
 *
 * <p>Listens AFTER_COMMIT so a statement is only issued once the underlying
 * register change is durable — issuing on a transaction that later rolls back
 * would send the holder a statement for a change that never happened. The
 * service itself decides eligibility (single entry + consumer), so these
 * listeners fire unconditionally for every holder.
 */
@Component
class RegisterStatementEventListener {

    private static final Logger log = LoggerFactory.getLogger(RegisterStatementEventListener.class);

    private final RegisterStatementService statementService;
    private final MeterRegistry meters;

    RegisterStatementEventListener(RegisterStatementService statementService, MeterRegistry meters) {
        this.statementService = statementService;
        this.meters = meters;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onHolderEntered(HolderEnteredEvent event) {
        safeIssue(event.holderId(), StatementTrigger.INITIAL_ENTRY);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onHolderRegisterChanged(HolderRegisterChangedEvent event) {
        safeIssue(event.holderId(), StatementTrigger.CHANGE);
    }

    /**
     * Register changes driven purely by indexed on-chain activity — without this, a position
     * that changed only through on-chain transfers (no operator/issuer action) would silently
     * never trigger its §19(2) statement.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onHolderBalanceSynced(HolderBalanceSyncedEvent event) {
        safeIssue(event.holderId(), event.newlyCreated() ? StatementTrigger.INITIAL_ENTRY : StatementTrigger.CHANGE);
    }

    private void safeIssue(java.util.UUID holderId, StatementTrigger trigger) {
        try {
            statementService.issueForHolder(holderId, trigger);
        } catch (RegisterNotReconciledException e) {
            // 9A-05: refused on purpose while the asset's holder sync is BLOCKED. Not re-issued automatically;
            // the operator can issue it through RegisterStatementController once the register is reconciled.
            StatementRefusalMetrics.unreconciled(meters).increment();
            log.warn("{} statement for holder {} refused, register unreconciled: {}", trigger, holderId, e.getMessage());
        } catch (Exception e) {
            // Never let statement issuance break the register operation that triggered it.
            log.error("Failed to issue {} statement for holder {}: {}",
                    trigger, holderId, e.getMessage());
        }
    }
}
