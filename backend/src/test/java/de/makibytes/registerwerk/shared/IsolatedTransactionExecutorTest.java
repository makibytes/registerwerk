package de.makibytes.registerwerk.shared;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IsolatedTransactionExecutorTest {

    @Test
    void eachWorkItemUsesAndCommitsItsOwnRequiresNewTransaction() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        IsolatedTransactionExecutor executor = new IsolatedTransactionExecutor(manager);

        executor.run(() -> { });

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(manager).commit(status);
    }

    @Test
    void aFailedWorkItemRollsBackAndEscapesToTheSchedulerCatchBoundary() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        IsolatedTransactionExecutor executor = new IsolatedTransactionExecutor(manager);

        assertThatThrownBy(() -> executor.run(() -> {
            throw new IllegalStateException("journal failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("journal failed");

        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }
}
