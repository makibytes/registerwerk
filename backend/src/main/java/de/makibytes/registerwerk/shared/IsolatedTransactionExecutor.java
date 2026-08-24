package de.makibytes.registerwerk.shared;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes one independently retryable unit of scheduled reconciliation work.
 *
 * <p>Pollers must catch failures <em>after</em> this boundary returns.  In particular, a joined
 * {@code ChainEffectRecorder} failure marks this physical transaction rollback-only, so the
 * owning projection mutation cannot commit without its compensation journal.  A bad row also
 * cannot roll back successful rows processed earlier in the same scheduler tick.
 */
@Component
public class IsolatedTransactionExecutor {

    @FunctionalInterface
    public interface Work {
        void run() throws Exception;
    }

    private final TransactionTemplate transaction;

    public IsolatedTransactionExecutor(PlatformTransactionManager transactionManager) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void run(Work work) {
        transaction.executeWithoutResult(status -> {
            try {
                work.run();
            } catch (RuntimeException runtime) {
                throw runtime;
            } catch (Exception checked) {
                throw new IsolatedWorkException(checked);
            }
        });
    }

    private static final class IsolatedWorkException extends RuntimeException {
        private IsolatedWorkException(Exception cause) {
            super(cause.getMessage(), cause);
        }
    }
}
