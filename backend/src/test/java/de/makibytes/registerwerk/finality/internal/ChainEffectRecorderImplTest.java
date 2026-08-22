package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChainEffectRecorderImpl — idempotent journal writes via native upsert")
class ChainEffectRecorderImplTest {

    @Mock private ChainEffectRepository repository;
    @Mock private CompensationDispatcher dispatcher;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ObjectMapper objectMapper;

    private ChainEffectRecorderImpl recorder;

    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID entityId = UUID.randomUUID();

    private ChainEffectDescriptor descriptor() {
        return ChainEffectDescriptor.of(chainConfigId, 100L, "0xhash", null,
                "indexer", "HOLDER_BALANCE_SYNCED", "Asset", entityId, entityId,
                CompensationCategory.RECOMPUTE);
    }

    @BeforeEach
    void setUp() {
        recorder = new ChainEffectRecorderImpl(repository, dispatcher, jdbcTemplate, objectMapper);
    }

    @Test
    @DisplayName("a new descriptor is inserted and its id returned")
    void recordInsertsNewRow() {
        UUID insertedId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenReturn(insertedId);

        UUID id = recorder.record(descriptor());

        assertThat(id).isEqualTo(insertedId);
        verify(repository, never()).findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any());
    }

    @Test
    @DisplayName("ON CONFLICT DO NOTHING (no row returned) falls back to the existing row instead of failing")
    void conflictFallsBackToExistingRow() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));
        UUID existingId = UUID.randomUUID();
        when(repository.findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any()))
                .thenReturn(Optional.of(existingRowWithId(existingId)));

        UUID id = recorder.record(descriptor());

        assertThat(id).isEqualTo(existingId);
    }

    @Test
    @DisplayName("a conflict with no matching row anywhere fails loud instead of silently returning null")
    void conflictWithNoExistingRowThrows() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));
        when(repository.findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> recorder.record(descriptor()))
                .isInstanceOf(IllegalStateException.class);
    }

    private ChainEffect existingRowWithId(UUID id) {
        ChainEffect row = new ChainEffect();
        org.springframework.test.util.ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    @Test
    @DisplayName("recordAndCompensate records then immediately dispatches compensation for the resulting id")
    void recordAndCompensateDispatchesImmediately() {
        UUID insertedId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenReturn(insertedId);
        when(dispatcher.compensate(insertedId)).thenReturn(new CompensationOutcome.Compensated("ok"));

        CompensationOutcome outcome = recorder.recordAndCompensate(descriptor());

        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
        verify(dispatcher).compensate(insertedId);
    }
}
