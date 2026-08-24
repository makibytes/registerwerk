package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChainEffectRetryJob — automatic retry of COMPENSATION_FAILED rows below the attempt cap")
class ChainEffectRetryJobTest {

    @Mock private ChainEffectRepository repository;
    @Mock private ChainEffectRetryExecutor retryExecutor;

    private ChainEffectRetryJob job;

    @BeforeEach
    void setUp() {
        job = new ChainEffectRetryJob(repository, retryExecutor);
    }

    @Test
    @DisplayName("retries every eligible row via the dispatcher, using the documented attempt cap")
    void retriesEveryEligibleRow() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.findRetryableIds(
                eq(ChainEffect.Status.COMPENSATION_FAILED), eq(ChainEffectRetryJob.MAX_AUTO_RETRY_ATTEMPTS)))
                .thenReturn(List.of(first, second));
        when(retryExecutor.retry(any())).thenReturn(new CompensationOutcome.Compensated("ok"));

        job.retryFailed();

        verify(retryExecutor).retry(first);
        verify(retryExecutor).retry(second);
    }

    @Test
    @DisplayName("one row throwing does not stop the remaining rows in the same pass from being retried")
    void oneFailureDoesNotAbortTheRestOfThePass() {
        UUID throwing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        when(repository.findRetryableIds(any(), anyInt())).thenReturn(List.of(throwing, ok));
        when(retryExecutor.retry(throwing)).thenThrow(new RuntimeException("boom"));
        when(retryExecutor.retry(ok)).thenReturn(new CompensationOutcome.Compensated("ok"));

        job.retryFailed();

        verify(retryExecutor, times(1)).retry(throwing);
        verify(retryExecutor, times(1)).retry(ok);
    }

    @Test
    @DisplayName("no eligible rows means the dispatcher is never called")
    void noEligibleRowsCallsNothing() {
        when(repository.findRetryableIds(any(), anyInt()))
                .thenReturn(List.of());

        job.retryFailed();

        verify(retryExecutor, never()).retry(any());
    }

    @Test
    @DisplayName("each retry is explicitly isolated in a REQUIRES_NEW physical transaction")
    void retryExecutorUsesIndependentTransactions() throws Exception {
        Method retry = ChainEffectRetryExecutor.class.getDeclaredMethod("retry", UUID.class);
        Transactional transactional = retry.getAnnotation(Transactional.class);

        org.assertj.core.api.Assertions.assertThat(transactional).isNotNull();
        org.assertj.core.api.Assertions.assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        org.assertj.core.api.Assertions.assertThat(
                ChainEffectRetryJob.class.getDeclaredMethod("retryFailed").getAnnotation(Transactional.class))
                .isNull();
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
