package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ChainQuarantinePort;
import de.makibytes.registerwerk.finality.api.ChainQuarantinedException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChainSubmissionExecutorImplTest {

    @Test
    void checksQuarantineInsideTransactionBeforeRunningSubmission() {
        UUID chainId = UUID.randomUUID();
        ChainQuarantinePort quarantine = mock(ChainQuarantinePort.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        ChainSubmissionExecutorImpl executor = new ChainSubmissionExecutorImpl(quarantine, manager);
        AtomicBoolean submitted = new AtomicBoolean();

        String result = executor.execute(chainId, () -> {
            submitted.set(true);
            return "tx";
        });

        assertThat(result).isEqualTo("tx");
        assertThat(submitted).isTrue();
        InOrder order = inOrder(manager, quarantine);
        order.verify(manager).getTransaction(any());
        order.verify(quarantine).requireSubmissionAllowed(chainId);
        verify(manager).commit(status);
    }

    @Test
    void quarantinedChainNeverRunsSubmissionCallback() {
        UUID chainId = UUID.randomUUID();
        ChainQuarantinePort quarantine = mock(ChainQuarantinePort.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        org.mockito.Mockito.doThrow(new ChainQuarantinedException(chainId))
                .when(quarantine).requireSubmissionAllowed(chainId);
        ChainSubmissionExecutorImpl executor = new ChainSubmissionExecutorImpl(quarantine, manager);
        AtomicBoolean submitted = new AtomicBoolean();

        assertThatThrownBy(() -> executor.execute(chainId, () -> {
            submitted.set(true);
            return "tx";
        })).isInstanceOf(ChainQuarantinedException.class);

        assertThat(submitted).isFalse();
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }
}
