package de.makibytes.registerwerk.webhook.internal;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Lets {@code TransactionTemplate}-based services run in plain unit tests. */
final class NoopTransactionManager implements PlatformTransactionManager {
    @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
    @Override public void commit(TransactionStatus status) { }
    @Override public void rollback(TransactionStatus status) { }
}
