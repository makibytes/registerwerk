package de.makibytes.registerwerk.idempotency.internal;

import de.makibytes.registerwerk.idempotency.api.IdempotencyRecord;
import de.makibytes.registerwerk.idempotency.api.IdempotencyRecordRepository;
import de.makibytes.registerwerk.idempotency.api.IdempotencyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService unit tests (Track 6-2)")
class IdempotencyServiceTest {

    @Mock private IdempotencyRecordRepository repository;

    private IdempotencyService service;

    private final UUID entityId = UUID.randomUUID();
    private final String key = "req-123";
    private final String hash = "abc123hash";

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository);
    }

    @Test
    @DisplayName("checkOrStart creates a new IN_PROGRESS record and returns Proceed when no prior record exists")
    void checkOrStart_noExistingRecord_proceeds() {
        when(repository.findForUpdate(entityId, key)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(IdempotencyRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        IdempotencyService.Outcome outcome = service.checkOrStart(entityId, key, hash);

        assertThat(outcome).isInstanceOf(IdempotencyService.Outcome.Proceed.class);
        verify(repository).saveAndFlush(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("checkOrStart replays a COMPLETED record with a matching request hash")
    void checkOrStart_completedMatchingHash_replays() {
        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setRequestHash(hash);
        existing.setStatus(IdempotencyStatus.COMPLETED);
        existing.setResponseStatus(201);
        existing.setResponseBody("{\"id\":\"abc\"}");
        when(repository.findForUpdate(entityId, key)).thenReturn(Optional.of(existing));

        IdempotencyService.Outcome outcome = service.checkOrStart(entityId, key, hash);

        assertThat(outcome).isInstanceOf(IdempotencyService.Outcome.Replay.class);
        IdempotencyService.Outcome.Replay replay = (IdempotencyService.Outcome.Replay) outcome;
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo("{\"id\":\"abc\"}");
    }

    @Test
    @DisplayName("checkOrStart returns a 422 Conflict when the key is reused with a different request hash")
    void checkOrStart_differentHash_conflict422() {
        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setRequestHash("different-hash");
        existing.setStatus(IdempotencyStatus.COMPLETED);
        when(repository.findForUpdate(entityId, key)).thenReturn(Optional.of(existing));

        IdempotencyService.Outcome outcome = service.checkOrStart(entityId, key, hash);

        assertThat(outcome).isInstanceOf(IdempotencyService.Outcome.Conflict.class);
        assertThat(((IdempotencyService.Outcome.Conflict) outcome).httpStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("checkOrStart returns a 409 Conflict when a matching-hash request is still IN_PROGRESS")
    void checkOrStart_inProgress_conflict409() {
        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setRequestHash(hash);
        existing.setStatus(IdempotencyStatus.IN_PROGRESS);
        when(repository.findForUpdate(entityId, key)).thenReturn(Optional.of(existing));

        IdempotencyService.Outcome outcome = service.checkOrStart(entityId, key, hash);

        assertThat(outcome).isInstanceOf(IdempotencyService.Outcome.Conflict.class);
        assertThat(((IdempotencyService.Outcome.Conflict) outcome).httpStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("checkOrStart returns a 409 Conflict when it loses the insert race to a concurrent duplicate")
    void checkOrStart_raceOnInsert_conflict409() {
        when(repository.findForUpdate(entityId, key)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(IdempotencyRecord.class))).thenThrow(new DataIntegrityViolationException("dup"));

        IdempotencyService.Outcome outcome = service.checkOrStart(entityId, key, hash);

        assertThat(outcome).isInstanceOf(IdempotencyService.Outcome.Conflict.class);
        assertThat(((IdempotencyService.Outcome.Conflict) outcome).httpStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("complete marks the record COMPLETED and stores the response for a non-5xx status")
    void complete_nonServerError_marksCompleted() {
        UUID recordId = UUID.randomUUID();
        IdempotencyRecord record = new IdempotencyRecord();
        when(repository.findById(recordId)).thenReturn(Optional.of(record));

        service.complete(recordId, 200, "{\"ok\":true}");

        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getResponseStatus()).isEqualTo(200);
        assertThat(record.getResponseBody()).isEqualTo("{\"ok\":true}");
        assertThat(record.getCompletedAt()).isNotNull();
        verify(repository).save(record);
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("complete deletes the record on a 5xx status, allowing a fresh retry")
    void complete_serverError_deletesRecord() {
        UUID recordId = UUID.randomUUID();
        IdempotencyRecord record = new IdempotencyRecord();
        when(repository.findById(recordId)).thenReturn(Optional.of(record));

        service.complete(recordId, 500, "{\"error\":\"internal\"}");

        verify(repository).delete(record);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("complete is a no-op when the record has disappeared")
    void complete_missingRecord_isNoOp() {
        UUID recordId = UUID.randomUUID();
        when(repository.findById(recordId)).thenReturn(Optional.empty());

        service.complete(recordId, 200, "body");

        verify(repository, never()).save(any());
        verify(repository, never()).delete(any(IdempotencyRecord.class));
    }
}
