package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
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
    @Mock private CompensationDispatcher dispatcher;

    private ChainEffectRetryJob job;

    @BeforeEach
    void setUp() {
        job = new ChainEffectRetryJob(repository, dispatcher);
    }

    private ChainEffect row(UUID id) {
        ChainEffect row = new ChainEffect();
        ReflectionTestUtils.setField(row, "id", id);
        row.setRecordedAt(Instant.now());
        return row;
    }

    @Test
    @DisplayName("retries every eligible row via the dispatcher, using the documented attempt cap")
    void retriesEveryEligibleRow() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.findByStatusAndAttemptCountLessThanOrderByRecordedAtAsc(
                eq(ChainEffect.Status.COMPENSATION_FAILED), eq(ChainEffectRetryJob.MAX_AUTO_RETRY_ATTEMPTS)))
                .thenReturn(List.of(row(first), row(second)));
        when(dispatcher.compensate(any())).thenReturn(new CompensationOutcome.Compensated("ok"));

        job.retryFailed();

        verify(dispatcher).compensate(first);
        verify(dispatcher).compensate(second);
    }

    @Test
    @DisplayName("one row throwing does not stop the remaining rows in the same pass from being retried")
    void oneFailureDoesNotAbortTheRestOfThePass() {
        UUID throwing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        when(repository.findByStatusAndAttemptCountLessThanOrderByRecordedAtAsc(any(), anyInt()))
                .thenReturn(List.of(row(throwing), row(ok)));
        when(dispatcher.compensate(throwing)).thenThrow(new RuntimeException("boom"));
        when(dispatcher.compensate(ok)).thenReturn(new CompensationOutcome.Compensated("ok"));

        job.retryFailed();

        verify(dispatcher, times(1)).compensate(throwing);
        verify(dispatcher, times(1)).compensate(ok);
    }

    @Test
    @DisplayName("no eligible rows means the dispatcher is never called")
    void noEligibleRowsCallsNothing() {
        when(repository.findByStatusAndAttemptCountLessThanOrderByRecordedAtAsc(any(), anyInt()))
                .thenReturn(List.of());

        job.retryFailed();

        verify(dispatcher, never()).compensate(any());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
