package de.makibytes.registerwerk.shared;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers a side effect until the surrounding database transaction has committed.
 *
 * <p>Used for broadcasting blockchain transactions from {@code @Transactional} service
 * methods: a chain transaction is irreversible the moment it is sent, so sending it
 * before the DB commit means a rollback (e.g. a failed audit write, which deliberately
 * propagates) leaves the chain ahead of the database. With this helper the DB state is
 * the committed source of intent and the broadcast follows; if the broadcast itself
 * fails, the row's missing tx hash marks it for retry by the module's poller (or the
 * drift reconciliation catches the divergence).
 *
 * <p>Outside a transaction the action runs immediately.
 */
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
